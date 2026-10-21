package io.wasmexample.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

public final class PluginEnvelope {
    public static final int ABI_VERSION = 1;

    private PluginEnvelope() {
    }

    @RegisterForReflection
    public record Request(int abiVersion, long nowMillis, JsonNode input) {
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(int abiVersion, JsonNode result, List<String> logs, String error) {
    }
}
