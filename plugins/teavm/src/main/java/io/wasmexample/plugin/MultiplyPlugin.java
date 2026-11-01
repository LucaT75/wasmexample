package io.wasmexample.plugin;

import org.teavm.interop.Export;

public final class MultiplyPlugin {
    public static void main(String[] args) {
    }

    @Export(name = "alloc")
    public static int alloc(int size) {
        return ArithmeticPluginSupport.alloc(size);
    }

    @Export(name = "multiply")
    public static long multiply(int pointer, int length) {
        return ArithmeticPluginSupport.execute(pointer, length, 0);
    }
}
