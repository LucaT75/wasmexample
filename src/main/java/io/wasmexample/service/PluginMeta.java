package io.wasmexample.service;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record PluginMeta(String pluginName, String sha256, int abiVersion) {
}
