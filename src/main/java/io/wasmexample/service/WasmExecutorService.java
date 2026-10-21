package io.wasmexample.service;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.WasmModule;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.wasmexample.repository.PluginEntity;
import io.wasmexample.repository.PluginRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class WasmExecutorService {
    private static final Logger LOG = LoggerFactory.getLogger(WasmExecutorService.class);
    private static final long WASM_PAGE_SIZE = 65_536L;
    private static final int MAX_INPUT_BYTES = 1_048_576;
    private static final int MAX_OUTPUT_BYTES = 1_048_576;
    private static final int MAX_MEMORY_PAGES = 256;
    private static final int MAX_PLUGIN_LOGS = 50;
    private static final int MAX_PLUGIN_LOG_LENGTH = 1_000;
    private static final int BREAKER_THRESHOLD = 5;
    private static final Duration BREAKER_COOLDOWN = Duration.ofSeconds(30);
    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(2);

    private record CacheKey(String pluginName, String sha256, int abiVersion) {
    }

    @Inject
    PluginRepository repository;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WasmPluginValidator validator;

    @Inject
    MeterRegistry meterRegistry;

    private final ConcurrentHashMap<CacheKey, WasmPluginValidator.ValidatedModule> moduleCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CacheKey, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private ThreadPoolExecutor executor;

    @PostConstruct
    void initialize() {
        AtomicInteger threadId = new AtomicInteger();
        executor = new ThreadPoolExecutor(
                4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32),
                task -> {
                    Thread thread = new Thread(task, "wasm-plugin-" + threadId.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public void uploadPlugin(String pluginName, byte[] wasmBinary) {
        if (pluginName == null || pluginName.isBlank()) {
            throw new IllegalArgumentException("Il nome del plugin non può essere vuoto");
        }
        if (pluginName.length() > 64 || !pluginName.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Nome plugin non valido");
        }

        validator.validate(wasmBinary);
        String sha256 = WasmPluginValidator.sha256Hex(wasmBinary);
        repository.saveOrActivateVersion(
                pluginName, wasmBinary, sha256, PluginEnvelope.ABI_VERSION);
        evictPreviousVersions(pluginName, sha256);
    }

    public JsonNode executePlugin(String pluginName, String methodName, JsonNode input) {
        long startedAt = System.nanoTime();
        String pluginTag = "unknown";
        String methodTag = "unknown";
        String abiVersionTag = "unknown";
        String outcome = "ok";
        int inputSize = 0;
        int outputSize = 0;
        try {
            PluginMeta meta = repository.find("pluginName = ?1 and active = true", pluginName)
                    .project(PluginMeta.class)
                    .firstResult();
            if (meta == null) {
                throw new PluginNotFoundException("Plugin non trovato: " + pluginName);
            }

            pluginTag = meta.pluginName();
            abiVersionTag = Integer.toString(meta.abiVersion());
            if (meta.abiVersion() != PluginEnvelope.ABI_VERSION) {
                throw new PluginExecutionException(PluginExecutionException.Kind.UNSUPPORTED_ABI,
                        "Versione del contratto non supportata per il plugin: " + pluginName);
            }
            CacheKey key = new CacheKey(pluginName, meta.sha256(), meta.abiVersion());
            CircuitBreaker breaker = breakers.computeIfAbsent(key, ignored -> new CircuitBreaker());
            if (!breaker.allowRequest()) {
                throw new PluginExecutionException(PluginExecutionException.Kind.CIRCUIT_OPEN,
                        "Plugin temporaneamente disabilitato per errori ripetuti");
            }

            WasmPluginValidator.ValidatedModule validated = loadValidated(meta, key);
            validator.validateEntryPoint(validated, methodName);
            methodTag = methodName;

            byte[] requestBytes = serializeRequest(input);
            inputSize = requestBytes.length;
            if (inputSize > MAX_INPUT_BYTES) {
                throw new PluginExecutionException(PluginExecutionException.Kind.INPUT_TOO_LARGE,
                        "Input oltre il limite di " + MAX_INPUT_BYTES + " byte");
            }
            byte[] outputBytes = executeIsolated(
                    validated.module(), methodName, requestBytes, breaker);
            outputSize = outputBytes.length;
            JsonNode result = parseResponse(outputBytes, pluginName, breaker);
            breaker.recordSuccess();
            return result;
        } catch (PluginNotFoundException e) {
            outcome = "not_found";
            throw e;
        } catch (PluginExecutionException e) {
            outcome = e.kind().name().toLowerCase(Locale.ROOT);
            throw e;
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            meterRegistry.timer("wasm.plugin.execution",
                    "plugin", pluginTag,
                    "abi_version", abiVersionTag,
                    "method", methodTag,
                    "outcome", outcome)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
            recordSize("wasm.plugin.input.bytes", pluginTag, abiVersionTag, inputSize);
            recordSize("wasm.plugin.output.bytes", pluginTag, abiVersionTag, outputSize);
        }
    }

    public void activatePluginVersion(String pluginName, String sha256) {
        if (sha256 == null || !sha256.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("SHA-256 non valido");
        }
        String normalizedHash = sha256.toLowerCase(Locale.ROOT);
        PluginEntity version = repository.find(
                "pluginName = ?1 and sha256 = ?2 and abiVersion = ?3",
                pluginName, normalizedHash, PluginEnvelope.ABI_VERSION).firstResult();
        if (version == null) {
            throw new PluginNotFoundException("Versione plugin non trovata: " + pluginName);
        }
        if (!version.sha256.equalsIgnoreCase(WasmPluginValidator.sha256Hex(version.wasmBinary))) {
            throw new PluginExecutionException(PluginExecutionException.Kind.INVALID_MODULE,
                    "Hash del binario non corrispondente ai metadati");
        }
        validator.validate(version.wasmBinary);
        if (!repository.activateVersion(pluginName, version.sha256, version.abiVersion)) {
            throw new PluginNotFoundException("Versione plugin non trovata: " + pluginName);
        }
        evictPreviousVersions(pluginName, normalizedHash);
    }

    private WasmPluginValidator.ValidatedModule loadValidated(PluginMeta meta, CacheKey key) {
        WasmPluginValidator.ValidatedModule cached = moduleCache.get(key);
        if (cached != null) {
            return cached;
        }

        PluginEntity entity = repository.find(
                "pluginName = ?1 and sha256 = ?2 and abiVersion = ?3",
                meta.pluginName(), meta.sha256(), meta.abiVersion()).firstResult();
        if (entity == null || !entity.sha256.equalsIgnoreCase(
                WasmPluginValidator.sha256Hex(entity.wasmBinary))) {
            throw new PluginExecutionException(PluginExecutionException.Kind.INVALID_MODULE,
                    "Hash del binario non corrispondente ai metadati per " + meta.pluginName());
        }
        WasmPluginValidator.ValidatedModule validated = validator.validate(entity.wasmBinary);
        WasmPluginValidator.ValidatedModule previous = moduleCache.putIfAbsent(key, validated);
        return previous == null ? validated : previous;
    }

    private byte[] serializeRequest(JsonNode input) {
        PluginEnvelope.Request request = new PluginEnvelope.Request(
                PluginEnvelope.ABI_VERSION, System.currentTimeMillis(), input);
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (IOException e) {
            throw new PluginExecutionException(PluginExecutionException.Kind.INVALID_INPUT,
                    "Input non serializzabile in JSON", e);
        }
    }

    private byte[] executeIsolated(WasmModule module, String methodName, byte[] inputBytes,
            CircuitBreaker breaker) {
        Future<byte[]> future;
        try {
            future = executor.submit(() -> invoke(module, methodName, inputBytes));
        } catch (RejectedExecutionException e) {
            throw new PluginExecutionException(PluginExecutionException.Kind.OVERLOADED,
                    "Pool dei plugin saturo", e);
        }

        try {
            return future.get(EXECUTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            breaker.recordFailure();
            throw new PluginExecutionException(PluginExecutionException.Kind.TIMEOUT,
                    "Esecuzione plugin oltre il timeout di " + EXECUTION_TIMEOUT, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PluginExecutionException(PluginExecutionException.Kind.INTERRUPTED,
                    "Esecuzione plugin interrotta", e);
        } catch (ExecutionException e) {
            breaker.recordFailure();
            Throwable cause = e.getCause();
            if (cause instanceof PluginExecutionException executionException) {
                throw executionException;
            }
            throw new PluginExecutionException(PluginExecutionException.Kind.TRAP,
                    "Il plugin è terminato con un errore", cause);
        }
    }

    private byte[] invoke(WasmModule module, String methodName, byte[] inputBytes) {
        Instance instance = Instance.builder(module)
                .withMemoryLimits(WasmPluginValidator.memoryLimits(module, MAX_MEMORY_PAGES))
                .build();
        long[] allocation = instance.export("alloc").apply(inputBytes.length);
        if (allocation.length != 1) {
            throw contractViolation("alloc deve restituire un puntatore");
        }
        Memory memory = instance.memory();
        int inputPointer = checkedMemoryRange(
                allocation[0] & 0xFFFFFFFFL, inputBytes.length, memory, "area di input");
        memory.write(inputPointer, inputBytes);

        long[] result = instance.export(methodName).apply(inputPointer, inputBytes.length);
        if (result.length != 1) {
            throw contractViolation("Il metodo del plugin deve restituire un valore i64");
        }
        long packedOutput = result[0];
        long outputPointer = packedOutput & 0xFFFFFFFFL;
        long outputLength = packedOutput >>> Integer.SIZE;
        if (outputLength > MAX_OUTPUT_BYTES) {
            throw new PluginExecutionException(PluginExecutionException.Kind.OUTPUT_TOO_LARGE,
                    "Output oltre il limite di " + MAX_OUTPUT_BYTES + " byte");
        }
        int checkedOutputPointer = checkedMemoryRange(
                outputPointer, outputLength, memory, "area di output");
        return memory.readBytes(checkedOutputPointer, (int) outputLength);
    }

    private JsonNode parseResponse(byte[] bytes, String pluginName, CircuitBreaker breaker) {
        JsonNode output;
        try {
            output = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(bytes);
        } catch (IOException e) {
            breaker.recordFailure();
            throw new PluginExecutionException(PluginExecutionException.Kind.CONTRACT_VIOLATION,
                    "L'output del plugin non è JSON valido", e);
        }
        if (output == null || !output.isObject()) {
            breaker.recordFailure();
            throw contractViolation("La risposta del plugin deve essere un oggetto JSON");
        }

        PluginEnvelope.Response response;
        try {
            response = objectMapper.treeToValue(output, PluginEnvelope.Response.class);
        } catch (IOException e) {
            breaker.recordFailure();
            throw new PluginExecutionException(PluginExecutionException.Kind.CONTRACT_VIOLATION,
                    "L'output del plugin non rispetta il contratto JSON", e);
        }
        if (response.abiVersion() != PluginEnvelope.ABI_VERSION) {
            breaker.recordFailure();
            throw contractViolation("Versione del contratto nella risposta non valida");
        }
        logPluginMessages(pluginName, response.logs());
        if (response.error() != null && !response.error().isBlank()) {
            breaker.recordSuccess();
            throw new PluginExecutionException(PluginExecutionException.Kind.PLUGIN_ERROR,
                    truncate(response.error()));
        }
        if (response.result() == null) {
            breaker.recordFailure();
            throw contractViolation("La risposta deve contenere 'result' oppure 'error'");
        }
        return response.result();
    }

    private static int checkedMemoryRange(long pointer, long length, Memory memory, String label) {
        long memorySize = (long) memory.pages() * WASM_PAGE_SIZE;
        if (pointer < 0 || length < 0 || pointer > Integer.MAX_VALUE || length > Integer.MAX_VALUE
                || pointer > memorySize || length > memorySize - pointer) {
            throw contractViolation("Puntatore o lunghezza fuori dalla memoria (" + label + ")");
        }
        return (int) pointer;
    }

    private void logPluginMessages(String pluginName, List<String> messages) {
        if (messages == null) {
            return;
        }
        for (int i = 0; i < Math.min(messages.size(), MAX_PLUGIN_LOGS); i++) {
            String message = truncate(Objects.toString(messages.get(i), ""))
                    .replaceAll("[\\r\\n]+", " ");
            LOG.debug("plugin={} log: {}", pluginName, message);
        }
    }

    private void recordSize(String metric, String pluginName, String abiVersion, int size) {
        if (size > 0) {
            DistributionSummary.builder(metric)
                    .tag("plugin", pluginName)
                    .tag("abi_version", abiVersion)
                    .register(meterRegistry)
                    .record(size);
        }
    }

    private void evictPreviousVersions(String pluginName, String activeHash) {
        moduleCache.keySet().removeIf(key -> key.pluginName().equals(pluginName)
                && !key.sha256().equalsIgnoreCase(activeHash));
        breakers.keySet().removeIf(key -> key.pluginName().equals(pluginName)
                && !key.sha256().equalsIgnoreCase(activeHash));
    }

    private static String truncate(String value) {
        return value.length() <= MAX_PLUGIN_LOG_LENGTH
                ? value : value.substring(0, MAX_PLUGIN_LOG_LENGTH);
    }

    private static PluginExecutionException contractViolation(String message) {
        return new PluginExecutionException(
                PluginExecutionException.Kind.CONTRACT_VIOLATION, message);
    }

    public static class PluginNotFoundException extends IllegalArgumentException {
        public PluginNotFoundException(String message) {
            super(message);
        }
    }

    private static final class CircuitBreaker {
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long openUntil;

        boolean allowRequest() {
            return System.currentTimeMillis() >= openUntil;
        }

        void recordSuccess() {
            failures.set(0);
        }

        void recordFailure() {
            if (failures.incrementAndGet() >= BREAKER_THRESHOLD) {
                openUntil = System.currentTimeMillis() + BREAKER_COOLDOWN.toMillis();
7                failures.set(BREAKER_THRESHOLD - 1);
            }
        }
    }
}
