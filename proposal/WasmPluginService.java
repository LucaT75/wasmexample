package com.example.plugins; // adatta al tuo package

import static com.example.plugins.PluginExecutionException.Kind.CIRCUIT_OPEN;
import static com.example.plugins.PluginExecutionException.Kind.CONTRACT_VIOLATION;
import static com.example.plugins.PluginExecutionException.Kind.INPUT_TOO_LARGE;
import static com.example.plugins.PluginExecutionException.Kind.INTERRUPTED;
import static com.example.plugins.PluginExecutionException.Kind.INVALID_INPUT;
import static com.example.plugins.PluginExecutionException.Kind.INVALID_MODULE;
import static com.example.plugins.PluginExecutionException.Kind.OUTPUT_TOO_LARGE;
import static com.example.plugins.PluginExecutionException.Kind.OVERLOADED;
import static com.example.plugins.PluginExecutionException.Kind.PLUGIN_ERROR;
import static com.example.plugins.PluginExecutionException.Kind.TIMEOUT;
import static com.example.plugins.PluginExecutionException.Kind.TRAP;
import static com.example.plugins.PluginExecutionException.Kind.UNKNOWN_METHOD;
import static com.example.plugins.PluginExecutionException.Kind.UNSUPPORTED_ABI;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.WasmModule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Esecuzione di plugin WASM come funzioni pure "JSON in, JSON out", senza import.
 *
 * Contratto ABI v1 (vedi PluginEnvelope):
 *  - export di memoria "memory" e di funzione "alloc(len: i32) -> i32";
 *  - entry point "(ptr: i32, len: i32) -> i64", con puntatore nei 32 bit bassi e lunghezza
 *    nei 32 bit alti del valore restituito;
 *  - input e output sono JSON UTF-8.
 *
 * Presuppone che PluginEntity abbia i campi: pluginName, wasmBinary, sha256, abiVersion, active.
 */
@ApplicationScoped
public class WasmPluginService {

    private static final Logger LOG = LoggerFactory.getLogger(WasmPluginService.class);

    static final int ABI_VERSION = 1;

    private static final long WASM_PAGE_SIZE = 65_536L;
    private static final int MAX_PLUGIN_LOGS = 50;
    private static final int MAX_PLUGIN_LOG_LENGTH = 1_000;
mento non lascia mai cache stantia. */
    private record CacheKey(String name, String sha256) {
    }

    @Inject
    PluginRepository repository; // il tuo repository Panache esistente

    @Inject
    ObjectMapper objectMapper;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    WasmPluginValidator validator;

    @ConfigProperty(name = "wasm.plugins.timeout", defaultValue = "PT2S")
    Duration timeout; // end-to-end: include l'attesa in coda

    @ConfigProperty(name = "wasm.plugins.threads", defaultValue = "4")
    int threads;

    @ConfigProperty(name = "wasm.plugins.queue-size", defaultValue = "32")
    int queueSize;

    @ConfigProperty(name = "wasm.plugins.max-input-bytes", defaultValue = "1048576")
    int maxInputBytes;

    @ConfigProperty(name = "wasm.plugins.max-output-bytes", defaultValue = "1048576")
    int maxOutputBytes;

    @ConfigProperty(name = "wasm.plugins.breaker.failure-threshold", defaultValue = "5")
    int breakerThreshold;

    @ConfigProperty(name = "wasm.plugins.breaker.cooldown", defaultValue = "PT30S")
    Duration breakerCooldown;

    private final ConcurrentMap<CacheKey, WasmPluginValidator.ValidatedModule> moduleCache =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    // Bulkhead: pool dedicato e limitato. Se è saturo la richiesta viene rifiutata subito
    // (OVERLOADED) invece di consumare i thread dell'applicazione.
    private ThreadPoolExecutor executor;

    @PostConstruct
    void init() {
        AtomicInteger counter = new AtomicInteger();
        executor = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                r -> {
                    Thread t = new Thread(r, "wasm-plugin-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Esegue l'entry point {@code methodName} del plugin {@code pluginName}.
     *
     * @return il campo "result" della risposta del plugin
     * @throws PluginNotFoundException   plugin inesistente o non attivo
     * @throws PluginExecutionException  qualsiasi altro errore, categorizzato da Kind
     */
    public JsonNode execute(String pluginName, String methodName, Object input) {
        validateFactorsInput(input);

        Timer.Sample sample = Timer.start(meterRegistry);
        // Tag a bassa cardinalità: nome plugin e metodo entrano nelle metriche solo dopo
        // essere stati riconosciuti, mai direttamente dall'input della richiesta.
        String pluginTag = "unknown";
        String methodTag = "unknown";
        String outcome = "ok";
        try {
            PluginMeta meta = repository.find("pluginName = ?1 and active = true", pluginName)
                    .project(PluginMeta.class)
                    .firstResultOptional()
                    .orElseThrow(() -> new PluginNotFoundException("Plugin non trovato: " + pluginName));
            pluginTag = meta.pluginName();

            if (meta.abiVersion() != ABI_VERSION) {
                throw new PluginExecutionException(UNSUPPORTED_ABI,
                        "Il plugin '" + meta.pluginName() + "' usa ABI v" + meta.abiVersion()
                                + ", supportita solo v" + ABI_VERSION);
            }

            CircuitBreaker breaker = breakers.computeIfAbsent(meta.pluginName(),
                    n -> new CircuitBreaker(n, breakerThreshold, breakerCooldown));
            if (!breaker.allowRequest()) {
                throw new PluginExecutionException(CIRCUIT_OPEN,
                        "Plugin '" + meta.pluginName() + "' temporaneamente disabilitato per errori ripetuti");
            }

            WasmPluginValidator.ValidatedModule plugin = loadPlugin(meta);
            if (WasmPluginValidator.RESERVED_EXPORTS.contains(methodName)
                    || !plugin.functionExports().contains(methodName)) {
                throw new PluginExecutionException(UNKNOWN_METHOD,
                        "Il plugin '" + meta.pluginName() + "' non esporta il metodo richiesto");
            }
            methodTag = methodName;

            byte[] requestBytes = serializeRequest(input);
            byte[] outputBytes = runIsolated(plugin.module(), methodName, requestBytes, breaker);
            return parseResponse(outputBytes, meta.pluginName(), breaker);
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
            sample.stop(Timer.builder("wasm.plugin.execution")
                    .description("Durata delle esecuzioni dei plugin WASM")
                    .tag("plugin", pluginTag)
                    .tag("method", methodTag)
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }

    // ---------------------------------------------------------------- caricamento e cache

    private WasmPluginValidator.ValidatedModule loadPlugin(PluginMeta meta) {
        CacheKey key = new CacheKey(meta.pluginName(), meta.sha256());
        WasmPluginValidator.ValidatedModule cached = moduleCache.get(key);
        if (cached != null) {
            return cached;
        }

        // Lettura DB e parsing avvengono FUORI da qualunque lock della cache. Due richieste
        // concorrenti sul primo caricamento possono duplicare il lavoro: è innocuo.
        PluginEntity entity = repository
                .find("pluginName = ?1 and sha256 = ?2", meta.pluginName(), meta.sha256())
                .firstResult();
        if (entity == null) {
            throw new PluginNotFoundException("Binario non trovato per il plugin: " + meta.pluginName());
        }
        byte[] binary = entity.wasmBinary;

        if (!meta.sha256().equalsIgnoreCase(WasmPluginValidator.sha256Hex(binary))) {
            throw new PluginExecutionException(INVALID_MODULE,
                    "L'hash del binario non corrisponde ai metadati del plugin '" + meta.pluginName() + "'");
        }
        WasmPluginValidator.ValidatedModule validated = validator.validate(binary, List.of());

        WasmPluginValidator.ValidatedModule previous = moduleCache.putIfAbsent(key, validated);
        if (previous != null) {
            return previous;
        }
        // Una sola versione per plugin in cache: le precedenti vengono scartate.
        moduleCache.keySet().removeIf(k -> k.name().equals(key.name()) && !k.equals(key));
        LOG.info("Plugin '{}' caricato (sha256={})", meta.pluginName(), meta.sha256());
        return validated;
    }

    // ---------------------------------------------------------------- esecuzione isolata

    private byte[] serializeRequest(Object input) {
        try {
            PluginEnvelope.Request request = new PluginEnvelope.Request(
                    ABI_VERSION, System.currentTimeMillis(), objectMapper.valueToTree(input));
            byte[] bytes = objectMapper.writeValueAsBytes(request);
            if (bytes.length > maxInputBytes) {
                throw new PluginExecutionException(INPUT_TOO_LARGE,
                        "Input oltre il limite di " + maxInputBytes + " byte");
            }
            return bytes;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new PluginExecutionException(INVALID_INPUT, "Input non serializzabile in JSON", e);
        }
    }

    private byte[] runIsolated(WasmModule module, String methodName, byte[] inputBytes,
            CircuitBreaker breaker) {
        Future<byte[]> future;
        try {
            future = executor.submit(() -> invoke(module, methodName, inputBytes));
        } catch (RejectedExecutionException e) {
            // Saturazione = problema di capacità, non del plugin: non conta per il breaker.
            throw new PluginExecutionException(OVERLOADED, "Pool dei plugin saturo", e);
        }

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // cancel(true) interrompe il thread, ma l'interprete potrebbe non controllare
            // l'interruzione: in quel caso il thread resta occupato finché il modulo non termina.
            // È il motivo per cui il pool è limitato e c'è il circuit breaker.
            future.cancel(true);
            breaker.recordFailure();
            throw new PluginExecutionException(TIMEOUT, "Plugin oltre il timeout di " + timeout, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PluginExecutionException(INTERRUPTED, "Esecuzione interrotta", e);
        } catch (ExecutionException e) {
            breaker.recordFailure();
            Throwable cause = e.getCause();
            if (cause instanceof PluginExecutionException pe) {
                throw pe;
            }
            // Trap WASM (unreachable, out of bounds, stack overflow...) o errore dell'interprete.
            throw new PluginExecutionException(TRAP, "Il plugin è terminato con un errore: " + cause, cause);
        }
    }

    /** Gira nel thread del pool: nessuno stato condiviso, istanza fresca a ogni chiamata. */
    private byte[] invoke(WasmModule module, String methodName, byte[] inputBytes) {
        Instance instance = Instance.builder(module)
                // Nessun import: il contratto non ne ammette.
                // Performance: per la compilazione in bytecode aggiungi .withMachineFactory(...)
                // (modulo chicory compiler). Memoria: se la tua versione lo supporta, limita le
                // pagine con .withMemoryLimits(...).
                .build();
        Memory memory = instance.memory();

        long[] allocation = instance.export(WasmPluginValidator.ALLOC_EXPORT).apply(inputBytes.length);
        if (allocation.length != 1) {
            throw new PluginExecutionException(CONTRACT_VIOLATION,
                    "L'export 'alloc' deve restituire un puntatore");
        }
        int inputPointer = checkedMemoryRange(
                allocation[0] & 0xFFFFFFFFL, inputBytes.length, memory, "area di input");
        memory.write(inputPointer, inputBytes);

        long[] result = instance.export(methodName).apply(inputPointer, inputBytes.length);
        if (result.length != 1) {
            throw new PluginExecutionException(CONTRACT_VIOLATION,
                    "Il metodo del plugin deve restituire un valore i64");
        }

        long packedOutput = result[0];
        long outputPointer = packedOutput & 0xFFFFFFFFL;   // 32 bit bassi, senza segno
        long outputLength = packedOutput >>> Integer.SIZE; // 32 bit alti, senza segno
        if (outputLength > maxOutputBytes) {
            throw new PluginExecutionException(OUTPUT_TOO_LARGE,
                    "Output del plugin oltre il limite di " + maxOutputBytes + " byte");
        }
        int checkedOutputPointer = checkedMemoryRange(
                outputPointer, outputLength, memory, "area di output");
        return memory.readBytes(checkedOutputPointer, (int) outputLength);
    }

    private static int checkedMemoryRange(long pointer, long length, Memory memory, String label) {
        long memorySize = (long) memory.pages() * WASM_PAGE_SIZE;
        if (pointer < 0 || length < 0
                || pointer > Integer.MAX_VALUE || length > Integer.MAX_VALUE
                || pointer > memorySize || length > memorySize - pointer) {
            throw new PluginExecutionException(CONTRACT_VIOLATION,
                    "Puntatore o lunghezza fuori dalla memoria del plugin (" + label + ")");
        }
        return (int) pointer;
    }

    // ---------------------------------------------------------------- risposta

    private JsonNode parseResponse(byte[] outputBytes, String pluginName, CircuitBreaker breaker) {
        PluginEnvelope.Response response;
        try {
            response = objectMapper.readValue(outputBytes, PluginEnvelope.Response.class);
        } catch (IOException e) {
            breaker.recordFailure();
            throw new PluginExecutionException(CONTRACT_VIOLATION,
                    "L'output del plugin non è un JSON conforme al contratto", e);
        }
        if (response == null) {
            breaker.recordFailure();
            throw new PluginExecutionException(CONTRACT_VIOLATION, "L'output del plugin è vuoto");
        }

        breaker.recordSuccess();
        logPluginMessages(pluginName, response.logs());

        if (response.error() != null && !response.error().isBlank()) {
            // Errore applicativo atteso: il plugin ha funzionato, ha solo detto "no".
            throw new PluginExecutionException(PLUGIN_ERROR, truncate(response.error()));
        }
        return response.result() == null ? NullNode.getInstance() : response.result();
    }

    private void logPluginMessages(String pluginName, List<String> logs) {
        if (logs == null) {
            return;
        }
        int count = 0;
        for (String message : logs) {
            if (count++ >= MAX_PLUGIN_LOGS) {
                LOG.debug("plugin={} ha prodotto più di {} messaggi di log, il resto è scartato",
                        pluginName, MAX_PLUGIN_LOGS);
                break;
            }
            // I messaggi arrivano da codice non fidato: niente a-capo (log injection), lunghezza limitata.
            LOG.debug("plugin={} log: {}", pluginName,
                    truncate(Objects.toString(message, "")).replaceAll("[\\r\\n]+", " "));
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_PLUGIN_LOG_LENGTH ? text : text.substring(0, MAX_PLUGIN_LOG_LENGTH) + "…";
    }

    // ---------------------------------------------------------------- validazione input

    private void validateFactorsInput(Object input) {
        Objects.requireNonNull(input, "input");
        // TODO: incolla qui la tua validazione esistente, invariata.
    }

    // ---------------------------------------------------------------- circuit breaker

    /**
     * Breaker minimale per plugin: dopo N fallimenti consecutivi (timeout, trap, violazioni di
     * contratto) rifiuta le richieste per la durata del cooldown. Scaduto il cooldown le richieste
     * passano di nuovo e un solo fallimento riapre il circuito.
     */
    static final class CircuitBreaker {

        private final String pluginName;
        private final int failureThreshold;
        private final long cooldownMillis;
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private volatile long openUntilMillis;

        CircuitBreaker(String pluginName, int failureThreshold, Duration cooldown) {
            this.pluginName = pluginName;
            this.failureThreshold = failureThreshold;
            this.cooldownMillis = cooldown.toMillis();
        }

        boolean allowRequest() {
            return System.currentTimeMillis() >= openUntilMillis;
        }

        void recordSuccess() {
            consecutiveFailures.set(0);
        }

        void recordFailure() {
            if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
                openUntilMillis = System.currentTimeMillis() + cooldownMillis;
                consecutiveFailures.set(failureThreshold - 1);
                LOG.warn("Circuit breaker aperto per il plugin '{}' per {} ms", pluginName, cooldownMillis);
            }
        }
    }
}
