package com.example.plugins; // adatta al tuo package

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Proiezione leggera di PluginEntity: niente binario, così il lookup fatto a ogni chiamata
 * resta economico. I nomi dei componenti devono coincidere con i campi di PluginEntity
 * (requisito delle proiezioni Panache).
 */
@RegisterForReflection
public record PluginMeta(String pluginName, String sha256, int abiVersion) {
}
