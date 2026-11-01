package io.wasmexample.plugin;

import java.nio.charset.StandardCharsets;
import org.teavm.interop.Address;

final class ArithmeticPluginSupport {
    private static byte[] inputAllocation;
    private static byte[] outputAllocation;

    private ArithmeticPluginSupport() {
    }

    static int alloc(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("La dimensione richiesta non può essere negativa");
        }
        inputAllocation = new byte[size];
        return Address.ofData(inputAllocation).toInt();
    }

    static long execute(int pointer, int length, int operation) {
        try {
            Factors factors = parse(read(pointer, length));
            long value = switch (operation) {
                case 0 -> Math.multiplyExact(factors.first(), factors.second());
                case 1 -> Math.addExact(factors.first(), factors.second());
                default -> Math.subtractExact(factors.first(), factors.second());
            };
            return write("{\"abiVersion\":1,\"result\":{\"result\":" + value + "}}");
        } catch (RuntimeException e) {
            return write("{\"abiVersion\":1,\"error\":\"invalid or overflowing factors\"}");
        }
    }

    private static String read(int pointer, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Lunghezza negativa");
        }
        byte[] bytes = new byte[length];
        Address address = Address.fromInt(pointer);
        for (int i = 0; i < length; i++) {
            bytes[i] = address.add(i).getByte();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static long write(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        outputAllocation = new byte[bytes.length];
        int pointer = Address.ofData(outputAllocation).toInt();
        Address address = Address.fromInt(pointer);
        for (int i = 0; i < bytes.length; i++) {
            address.add(i).putByte(bytes[i]);
        }
        return ((long) bytes.length << Integer.SIZE) | (pointer & 0xffffffffL);
    }

    private static Factors parse(String json) {
        int position = 0;
        if (json.length() < 2 || json.charAt(position++) != '{') {
            throw new IllegalArgumentException("JSON non valido");
        }
        long first = 0;
        long second = 0;
        boolean hasFirst = false;
        boolean hasSecond = false;
        for (int field = 0; field < 2; field++) {
            while (position < json.length() && Character.isWhitespace(json.charAt(position))) {
                position++;
            }
            if (position >= json.length() || json.charAt(position++) != '"') {
                throw new IllegalArgumentException("Nome campo non valido");
            }
            int nameStart = position;
            while (position < json.length() && json.charAt(position) != '"') {
                position++;
            }
            String name = json.substring(nameStart, position++);
            while (position < json.length() && Character.isWhitespace(json.charAt(position))) {
                position++;
            }
            if (position >= json.length() || json.charAt(position++) != ':') {
                throw new IllegalArgumentException("Valore non valido");
            }
            while (position < json.length() && Character.isWhitespace(json.charAt(position))) {
                position++;
            }
            int valueStart = position;
            if (position < json.length() && json.charAt(position) == '-') {
                position++;
            }
            while (position < json.length() && Character.isDigit(json.charAt(position))) {
                position++;
            }
            long value = Long.parseLong(json.substring(valueStart, position));
            if ("factor1".equals(name) && !hasFirst) {
                first = value;
                hasFirst = true;
            } else if ("factor2".equals(name) && !hasSecond) {
                second = value;
                hasSecond = true;
            } else {
                throw new IllegalArgumentException("Campo inatteso o duplicato");
            }
            while (position < json.length() && Character.isWhitespace(json.charAt(position))) {
                position++;
            }
            if (field == 0 && (position >= json.length() || json.charAt(position++) != ',')) {
                throw new IllegalArgumentException("Campo mancante");
            }
        }
        while (position < json.length() && Character.isWhitespace(json.charAt(position))) {
            position++;
        }
        if (position >= json.length() || json.charAt(position++) != '}'
                || position != json.length() || !hasFirst || !hasSecond) {
            throw new IllegalArgumentException("Input incompleto");
        }
        return new Factors(first, second);
    }

    private record Factors(long first, long second) {
    }
}
