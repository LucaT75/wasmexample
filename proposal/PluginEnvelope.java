package com.example.plugins; // adatta al tuo package

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

/**
 * Contratto JSON host <-> plugin (ABI versione 1).
 *
 * Il plugin non ha import: tutto ciò che gli serve (ora, configurazione) arriva nella
 * richiesta, tutto ciò che vuole comunicare (log, errori applicativi) torna nella risposta.
 */
public final class PluginEnvelope {

    private PluginEnvelope() {
    }

    /** Ciò che l'host scrive nella memoria del plugin. */
    @RegisterForReflection
    public record Request(int abi, long nowMillis, JsonNode input) {
    }

    /**
     * Ciò che il plugin restituisce. Un 'error' valorizzato è un errore applicativo atteso,
     * non un guasto del plugin: non fa scattare il circuit breaker.
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(JsonNode result, List<String> logs, String error) {
    }
}
