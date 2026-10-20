package com.example.plugins; // adatta al tuo package

/**
 * Errore di esecuzione/validazione di un plugin, con una categoria (Kind) che l'endpoint
 * può mappare su un codice HTTP e che finisce come tag "outcome" nelle metriche.
 */
public class PluginExecutionException extends RuntimeException {

    public enum Kind {
        INVALID_INPUT,
        INPUT_TOO_LARGE,
        INVALID_MODULE,
        UNSUPPORTED_ABI,
        UNKNOWN_METHOD,
        CIRCUIT_OPEN,
        OVERLOADED,
        TIMEOUT,
        INTERRUPTED,
        TRAP,
        CONTRACT_VIOLATION,
        OUTPUT_TOO_LARGE,
        PLUGIN_ERROR
    }

    private final Kind kind;

    public PluginExecutionException(Kind kind, String message) {
        this(kind, message, null);
    }

    public PluginExecutionException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
