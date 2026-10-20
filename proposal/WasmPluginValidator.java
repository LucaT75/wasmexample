package com.example.plugins; // adatta al tuo package

import static com.example.plugins.PluginExecutionException.Kind.CONTRACT_VIOLATION;
import static com.example.plugins.PluginExecutionException.Kind.INTERRUPTED;
import static com.example.plugins.PluginExecutionException.Kind.INVALID_MODULE;
import static com.example.plugins.PluginExecutionException.Kind.TIMEOUT;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Validazione di un binario WASM rispetto al contratto "zero import".
 *
 * Va chiamato (1) all'upload, prima di marcare il plugin come attivo, e (2) dal servizio di
 * esecuzione al primo caricamento di una versione, come difesa in profondità.
 *
 * NOTA: i nomi dell'API di introspezione di Chicory (importSection/exportSection, ExternalType,
 * Memory.pages) cambiano tra le release: verificali contro la versione che usi.
 */
@ApplicationScoped
public class WasmPluginValidator {

    public static final int MAX_BINARY_BYTES = 8 * 1024 * 1024;
    public static final String MEMORY_EXPORT = "memory";
    public static final String ALLOC_EXPORT = "alloc";
    public static final Set<String> RESERVED_EXPORTS = Set.of(MEMORY_EXPORT, ALLOC_EXPORT);

    private static final long WASM_PAGE_SIZE = 65_536L;

    /** Modulo validato + insieme delle funzioni esportate (calcolato una volta sola). */
    public record ValidatedModule(WasmModule module, Set<String> functionExports) {
    }

    @ConfigProperty(name = "wasm.plugins.validation-timeout", defaultValue = "PT5S")
    Duration smokeTestTimeout;

    // Percorso raro (upload / primo caricamento). Un modulo con loop infinito nello start
    // function lascia un thread occupato: per questo il pool è senza limite ma daemon.
    private final ExecutorService smokeExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "wasm-plugin-validator");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        smokeExecutor.shutdownNow();
    }

    /**
     * @param binary       contenuto del file .wasm
     * @param entryPoints  funzioni che il plugin deve esportare (vuoto = solo alloc e memory)
     */
    public ValidatedModule validate(byte[] binary, Collection<String> entryPoints) {
        if (binary == null || binary.length == 0) {
            throw invalid("Binario WASM vuoto", null);
        }
        if (binary.length > MAX_BINARY_BYTES) {
            throw invalid("Binario WASM oltre il limite di " + MAX_BINARY_BYTES + " byte", null);
        }

        WasmModule module;
        try {
            module = Parser.parse(new ByteArrayInputStream(binary));
        } catch (Exception e) {
            throw invalid("Modulo WASM non valido (parsing fallito)", e);
        }

        // Contratto zero import: nessuna capacità sul sistema ospite.
        var imports = module.importSection();
        if (imports != null && imports.importCount() > 0) {
            throw invalid("Il plugin dichiara " + imports.importCount()
                    + " import: non ammessi dal contratto zero import", null);
        }

        Set<String> functions = new HashSet<>();
        Set<String> memories = new HashSet<>();
        var exports = module.exportSection();
        if (exports != null) {
            for (int i = 0; i < exports.exportCount(); i++) {
                var exp = exports.getExport(i);
                if (exp.exportType() == ExternalType.FUNCTION) {
                    functions.add(exp.name());
                } else if (exp.exportType() == ExternalType.MEMORY) {
                    memories.add(exp.name());
                }
            }
        }

        if (!memories.contains(MEMORY_EXPORT)) {
            throw invalid("Manca l'export di memoria '" + MEMORY_EXPORT + "'", null);
        }
        if (!functions.contains(ALLOC_EXPORT)) {
            throw invalid("Manca l'export di funzione '" + ALLOC_EXPORT + "'", null);
        }
        for (String entryPoint : entryPoints) {
            if (RESERVED_EXPORTS.contains(entryPoint) || !functions.contains(entryPoint)) {
                throw invalid("Manca l'entry point '" + entryPoint + "'", null);
            }
        }

        smokeTest(module);
        return new ValidatedModule(module, Set.copyOf(functions));
    }

    public static String sha256Hex(byte[] binary) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponibile", e);
        }
    }

    /**
     * Test di contratto minimale: il modulo si istanzia senza import e alloc(1) restituisce un
     * puntatore dentro la memoria. Estendi con i tuoi test "golden" (input/output registrati).
     */
    private void smokeTest(WasmModule module) {
        Future<?> future = smokeExecutor.submit(() -> {
            Instance instance = Instance.builder(module).build();
            long[] pointer = instance.export(ALLOC_EXPORT).apply(1);
            if (pointer.length != 1) {
                throw new PluginExecutionException(CONTRACT_VIOLATION,
                        "L'export 'alloc' deve restituire un solo valore");
            }
            long address = pointer[0] & 0xFFFFFFFFL;
            long memorySize = (long) instance.memory().pages() * WASM_PAGE_SIZE;
            if (address >= memorySize) {
                throw new PluginExecutionException(CONTRACT_VIOLATION,
                        "alloc ha restituito un puntatore fuori dalla memoria");
            }
        });

        try {
            future.get(smokeTestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new PluginExecutionException(TIMEOUT,
                    "Il test di contratto ha superato " + smokeTestTimeout, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PluginExecutionException(INTERRUPTED, "Validazione interrotta", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PluginExecutionException pe) {
                throw pe;
            }
            throw invalid("Il test di contratto è fallito: " + cause, cause);
        }
    }

    private static PluginExecutionException invalid(String message, Throwable cause) {
        return new PluginExecutionException(INVALID_MODULE, message, cause);
    }
}
