package io.wasmexample.controller;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class TeaVmFixture {
    private TeaVmFixture() {
    }

    static byte[] binary(String clockImport, boolean outOfMemory) {
        return binary(clockImport, outOfMemory, false);
    }

    static byte[] binary(String clockImport, boolean outOfMemory, boolean invalidClockSignature) {
        byte[] response = "{\"abiVersion\":1,\"result\":{\"result\":13}}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] log = "TeaVM diagnostic\n".getBytes(StandardCharsets.UTF_16LE);
        long now = System.currentTimeMillis();
        var module = new ByteArrayOutputStream();
        module.writeBytes(bytes(0, 97, 115, 109, 1, 0, 0, 0));
        section(module, 1, vector(
                bytes(0x60, 0, 1, invalidClockSignature ? 0x7e : 0x7c),
                bytes(0x60, 1, 0x7f, 0),
                bytes(0x60, 0, 0),
                bytes(0x60, 1, 0x7f, 1, 0x7f),
                bytes(0x60, 2, 0x7f, 0x7f, 1, 0x7e)));
        section(module, 2, vector(
                imported(clockImport, 0), imported("logString", 1),
                imported("logInt", 1), imported("logOutOfMemory", 2)));
        section(module, 3, vector(bytes(3), bytes(4), bytes(3), bytes(3), bytes(3)));
        section(module, 5, bytes(1, 0, 1));
        section(module, 7, vector(
                exported("memory", 2, 0), exported("alloc", 0, 4), exported("run", 0, 5),
                exported("teavm_stringData", 0, 6), exported("teavm_arrayLength", 0, 7),
                exported("teavm_charArrayData", 0, 8)));

        // Check the host clock's f64 encoding and epoch-millisecond units inside WASM.
        var run = new ByteArrayOutputStream();
        if (invalidClockSignature) {
            run.writeBytes(bytes(0, 0x10, 0, 0x1a));
        } else {
            run.writeBytes(bytes(1, 1, 0x7c, 0x10, 0, 0x22, 2, 0x44));
            run.writeBytes(f64(now - 60_000));
            run.writeBytes(bytes(0x63, 0x04, 0x40, 0, 0x0b, 0x20, 2, 0x44));
            run.writeBytes(f64(now + 60_000));
            run.writeBytes(bytes(0x64, 0x04, 0x40, 0, 0x0b));
        }
        run.writeBytes(bytes(0x41, 0, 0x10, 1, 0x41, 42, 0x10, 2));
        if (outOfMemory) {
            run.writeBytes(bytes(0x10, 3));
        }
        run.write(0x42);
        run.writeBytes(leb(((long) response.length << 32) | 256));
        run.write(0x0b);
        section(module, 10, vector(
                body(constant(1024)), sized(run.toByteArray()),
                body(constant(0)), body(constant(log.length / 2)), body(constant(512))));
        section(module, 11, vector(data(256, response), data(512, log)));
        return module.toByteArray();
    }

    private static byte[] imported(String name, int type) {
        return concat(text("teavm"), text(name), bytes(0, type));
    }

    private static byte[] exported(String name, int kind, int index) {
        return concat(text(name), bytes(kind, index));
    }

    private static byte[] constant(int value) {
        return concat(bytes(0x41), leb(value));
    }

    private static byte[] body(byte[] instructions) {
        return sized(concat(bytes(0), instructions, bytes(0x0b)));
    }

    private static byte[] data(int offset, byte[] content) {
        return concat(bytes(0), constant(offset), bytes(0x0b), sized(content));
    }

    private static void section(ByteArrayOutputStream module, int id, byte[] content) {
        module.write(id);
        module.writeBytes(sized(content));
    }

    private static byte[] text(String text) {
        return sized(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sized(byte[] content) {
        return concat(leb(content.length), content);
    }

    private static byte[] vector(byte[]... elements) {
        return concat(leb(elements.length), concat(elements));
    }

    private static byte[] concat(byte[]... parts) {
        var result = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            result.writeBytes(part);
        }
        return result.toByteArray();
    }

    private static byte[] bytes(int... values) {
        var result = new ByteArrayOutputStream();
        for (int value : values) {
            result.write(value);
        }
        return result.toByteArray();
    }

    private static byte[] f64(double value) {
        long bits = Double.doubleToRawLongBits(value);
        var result = new ByteArrayOutputStream();
        for (int i = 0; i < Double.BYTES; i++) {
            result.write((int) (bits >>> (i * 8)));
        }
        return result.toByteArray();
    }

    private static byte[] leb(long value) {
        var result = new ByteArrayOutputStream();
        boolean more;
        do {
            int next = (int) (value & 0x7f);
            value >>= 7;
            more = !((value == 0 && (next & 0x40) == 0)
                    || (value == -1 && (next & 0x40) != 0));
            result.write(more ? next | 0x80 : next);
        } while (more);
        return result.toByteArray();
    }
}
