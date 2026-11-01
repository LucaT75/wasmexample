package io.wasmexample.plugin;

import org.teavm.interop.Export;

public final class DiffPlugin {
    public static void main(String[] args) {
    }

    @Export(name = "alloc")
    public static int alloc(int size) {
        return ArithmeticPluginSupport.alloc(size);
    }

    @Export(name = "diff")
    public static long diff(int pointer, int length) {
        return ArithmeticPluginSupport.execute(pointer, length, 2);
    }
}
