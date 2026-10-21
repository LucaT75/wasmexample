package io.wasmexample.service;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.ValType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
public class WasmPluginValidator {
    public static final int MAX_BINARY_BYTES = 8 * 1024 * 1024;
    private static final long WASM_PAGE_SIZE = 65_536L;
    private static final Duration SMOKE_TEST_TIMEOUT = Duration.ofSeconds(2);

    public record ValidatedModule(WasmModule module, Set<String> functionExports) {
    }

    @Inject
    ObjectMapper objectMapper;

    private final ThreadPoolExecutor smokeExecutor = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "wasm-plugin-validator");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @PreDestroy
    void shutdown() {
        smokeExecutor.shutdownNow();
    }

    public ValidatedModule validate(byte[] binary) {
        if (binary == null || binary.length == 0 || binary.length > MAX_BINARY_BYTES) {
            throw invalid("Dimensione del binario WASM non valida", null);
        }

        WasmModule module;
        try {
            module = Parser.parse(new ByteArrayInputStream(binary));
        } catch (RuntimeException e) {
            throw invalid("Modulo WASM non valido (parsing fallito)", e);
        }
        if (module.importSection().importCount() != 0) {
            throw invalid("Il contratto JSON non ammette import WASM", null);
        }
        MemoryLimits limits = memoryLimits(module, 256);

        Set<String> functions = new HashSet<>();
        boolean exportsMemory = false;
        var exports = module.exportSection();
        for (int i = 0; i < exports.exportCount(); i++) {
            var export = exports.getExport(i);
            if (export.exportType() == ExternalType.FUNCTION) {
                functions.add(export.name());
            } else if (export.exportType() == ExternalType.MEMORY && "memory".equals(export.name())) {
                exportsMemory = true;
            }
        }
        if (!exportsMemory) {
            throw invalid("Il modulo deve esportare la memoria 'memory'", null);
        }
        requireSignature(module, "alloc", FunctionType.of(
                new ValType[] {ValType.I32}, new ValType[] {ValType.I32}));

        FunctionType entryPointType = FunctionType.of(
                new ValType[] {ValType.I32, ValType.I32}, new ValType[] {ValType.I64});
        Set<String> entryPoints = new HashSet<>();
        for (int i = 0; i < exports.exportCount(); i++) {
            var export = exports.getExport(i);
            if (export.exportType() == ExternalType.FUNCTION && !"alloc".equals(export.name())
                    && entryPointType.equals(functionType(module, export.index()))) {
                entryPoints.add(export.name());
            }
        }
        if (entryPoints.isEmpty()) {
            throw invalid("Il modulo deve esportare almeno una funzione (i32, i32) -> i64", null);
        }

        smokeTest(module, limits, entryPoints);
        return new ValidatedModule(module, Set.copyOf(functions));
    }

    public void validateEntryPoint(ValidatedModule validated, String methodName) {
        if (!validated.functionExports().contains(methodName)) {
            throw new PluginExecutionException(PluginExecutionException.Kind.UNKNOWN_METHOD,
                    "Il plugin non esporta il metodo richiesto");
        }
        requireSignature(validated.module(), methodName, FunctionType.of(
                new ValType[] {ValType.I32, ValType.I32}, new ValType[] {ValType.I64}));
    }

    public static String sha256Hex(byte[] binary) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponibile", e);
        }
    }

    public static MemoryLimits memoryLimits(WasmModule module, int maximumPages) {
        var memorySection = module.memorySection()
                .orElseThrow(() -> invalid("Il modulo non definisce memoria locale", null));
        if (memorySection.memoryCount() != 1) {
            throw invalid("È richiesta una singola memoria", null);
        }
        int initialPages = memorySection.getMemory(0).limits().initialPages();
        if (initialPages > maximumPages) {
            throw invalid("La memoria iniziale supera il limite di " + maximumPages + " pagine", null);
        }
        return new MemoryLimits(initialPages, maximumPages);
    }

    private static FunctionType functionType(WasmModule module, int functionIndex) {
        int importedFunctionIndex = 0;
        for (int i = 0; i < module.importSection().importCount(); i++) {
            var imported = module.importSection().getImport(i);
            if (imported.importType() == ExternalType.FUNCTION) {
                importedFunctionIndex++;
            }
        }
        int localIndex = functionIndex - importedFunctionIndex;
        if (localIndex < 0 || localIndex >= module.functionSection().functionCount()) {
            throw invalid("Indice di funzione WASM non valido", null);
        }
        return module.functionSection().getFunctionType(localIndex, module.typeSection());
    }

    private static void requireSignature(WasmModule module, String name, FunctionType expected) {
        var exports = module.exportSection();
        for (int i = 0; i < exports.exportCount(); i++) {
            var export = exports.getExport(i);
            if (name.equals(export.name()) && export.exportType() == ExternalType.FUNCTION) {
                if (!expected.equals(functionType(module, export.index()))) {
                    throw invalid("Firma WASM non valida per l'export '" + name + "'", null);
                }
                return;
            }
        }
        throw invalid("Export WASM mancante: " + name, null);
    }

    private void smokeTest(WasmModule module, MemoryLimits limits, Set<String> entryPoints) {
        Future<?> future;
        try {
            future = smokeExecutor.submit(() -> {
                Instance instance = Instance.builder(module).withMemoryLimits(limits).build();
                byte[] input = "{\"abiVersion\":1,\"nowMillis\":0,\"input\":{}}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                long[] allocated = instance.export("alloc").apply(input.length);
                if (allocated.length != 1) {
                    throw invalid("alloc deve restituire un puntatore", null);
                }
                int pointer = checkedRange(allocated[0] & 0xFFFFFFFFL, input.length, instance);
                instance.memory().write(pointer, input);

                for (String entryPoint : entryPoints) {
                    long[] result = instance.export(entryPoint).apply(pointer, input.length);
                    if (result.length != 1) {
                        throw invalid("L'entry point deve restituire un solo i64", null);
                    }
                    long outputPointer = result[0] & 0xFFFFFFFFL;
                    long outputLength = result[0] >>> Integer.SIZE;
                    if (outputLength == 0 || outputLength > 1_048_576) {
                        throw invalid("Output del test di contratto fuori limite", null);
                    }
                    int checkedPointer = checkedRange(outputPointer, outputLength, instance);
                    byte[] output = instance.memory().readBytes(checkedPointer, (int) outputLength);
                    PluginEnvelope.Response response;
                    try {
                        response = objectMapper.reader()
                                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readValue(output, PluginEnvelope.Response.class);
                    } catch (IOException e) {
                        throw invalid("Output del test di contratto non è JSON valido", e);
                    }
                    if (response == null || response.abiVersion() != PluginEnvelope.ABI_VERSION
                            || (response.result() == null
                                    && (response.error() == null || response.error().isBlank()))) {
                        throw invalid("Output del test di contratto non rispetta l'ABI JSON", null);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            throw invalid("Pool di validazione WASM saturo", e);
        }

        try {
            future.get(SMOKE_TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw invalid("Test di contratto WASM oltre il timeout", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw invalid("Validazione WASM interrotta", e);
        } catch (ExecutionException e) {
            throw invalid("Test di contratto WASM fallito", e.getCause());
        }
    }

    private static int checkedRange(long pointer, long length, Instance instance) {
        long memorySize = (long) instance.memory().pages() * WASM_PAGE_SIZE;
        if (pointer < 0 || length < 0 || pointer > Integer.MAX_VALUE || length > Integer.MAX_VALUE
                || pointer > memorySize || length > memorySize - pointer) {
            throw invalid("Puntatore o lunghezza fuori memoria nel test di contratto", null);
        }
        return (int) pointer;
    }

    private static PluginExecutionException invalid(String message, Throwable cause) {
        return new PluginExecutionException(
                PluginExecutionException.Kind.INVALID_MODULE, message, cause);
    }
}
