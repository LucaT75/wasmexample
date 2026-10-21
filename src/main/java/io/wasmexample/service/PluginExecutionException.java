package io.wasmexample.service;

public class PluginExecutionException extends RuntimeException {
    public enum Kind {
        INVALID_MODULE,
        INVALID_INPUT,
        UNSUPPORTED_ABI,
        UNKNOWN_METHOD,
        INPUT_TOO_LARGE,
        OUTPUT_TOO_LARGE,
        CIRCUIT_OPEN,
        OVERLOADED,
        TIMEOUT,
        INTERRUPTED,
        TRAP,
        CONTRACT_VIOLATION,
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
