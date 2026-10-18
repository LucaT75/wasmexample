package io.wasmexample.plugin;

import java.nio.charset.StandardCharsets;

import org.teavm.interop.Address;
import org.teavm.interop.Export;

public class SumPlugin {
    private static byte[] inputAllocation;
    private static byte[] outputAllocation;

    public static void main(String[] args) {
    }

    @Export(name = "alloc")
    public static int alloc(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("La dimensione richiesta non può essere negativa");
        }
        inputAllocation = new byte[size];
        return Address.ofData(inputAllocation).toInt();
    }

    @Export(name = "sum")
    public static long sum(int inputPointer, int inputLength) {
        if (inputLength < 0) {
            throw new IllegalArgumentException("La lunghezza dell'input non può essere negativa");
        }
        String jsonInput = readFromMemory(inputPointer, inputLength);
        Factors factors = new FactorsParser(jsonInput).parse();
        long sum = Math.addExact(factors.factor1, factors.factor2);
        byte[] outputBytes = ("{\"result\":" + sum + "}").getBytes(StandardCharsets.UTF_8);
        outputAllocation = new byte[outputBytes.length];
        int outputPointer = Address.ofData(outputAllocation).toInt();
        writeToMemory(outputPointer, outputBytes);
        return ((long) outputBytes.length << Integer.SIZE) | (outputPointer & 0xffffffffL);
    }

    private static String readFromMemory(int pointer, int length) {
        byte[] bytes = new byte[length];
        Address address = Address.fromInt(pointer);
        for (int i = 0; i < length; i++) {
            bytes[i] = address.add(i).getByte();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeToMemory(int pointer, byte[] bytes) {
        Address address = Address.fromInt(pointer);
        for (int i = 0; i < bytes.length; i++) {
            address.add(i).putByte(bytes[i]);
        }
    }

    private static final class Factors {
        private final long factor1;
        private final long factor2;

        private Factors(long factor1, long factor2) {
            this.factor1 = factor1;
            this.factor2 = factor2;
        }
    }

    private static final class FactorsParser {
        private final String json;
        private int position;
        private long factor1;
        private long factor2;
        private boolean hasFactor1;
        private boolean hasFactor2;

        private FactorsParser(String json) {
            this.json = json;
        }

        private Factors parse() {
            skipWhitespace();
            expect('{');
            skipWhitespace();
            parseField();
            skipWhitespace();
            expect(',');
            skipWhitespace();
            parseField();
            skipWhitespace();
            expect('}');
            skipWhitespace();
            if (position != json.length() || !hasFactor1 || !hasFactor2) {
                throw new IllegalArgumentException("L'input deve contenere factor1 e factor2");
            }
            return new Factors(factor1, factor2);
        }

        private void parseField() {
            String name = parseName();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            long value = parseInteger();
            if ("factor1".equals(name) && !hasFactor1) {
                factor1 = value;
                hasFactor1 = true;
            } else if ("factor2".equals(name) && !hasFactor2) {
                factor2 = value;
                hasFactor2 = true;
            } else {
                throw new IllegalArgumentException("Campo inatteso o duplicato: " + name);
            }
        }

        private String parseName() {
            expect('"');
            int start = position;
            while (position < json.length() && json.charAt(position) != '"') {
                position++;
            }
            if (position == json.length()) {
                throw new IllegalArgumentException("Nome campo JSON non terminato");
            }
            String name = json.substring(start, position);
            position++;
            return name;
        }

        private long parseInteger() {
            int start = position;
            if (position < json.length() && json.charAt(position) == '-') {
                position++;
            }
            int digitsStart = position;
            while (position < json.length() && json.charAt(position) >= '0'
                    && json.charAt(position) <= '9') {
                position++;
            }
            if (digitsStart == position || (position - digitsStart > 1
                    && json.charAt(digitsStart) == '0')) {
                throw new IllegalArgumentException("I fattori devono essere numeri interi");
            }
            try {
                return Long.parseLong(json.substring(start, position));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Fattore fuori dall'intervallo supportato", e);
            }
        }

        private void skipWhitespace() {
            while (position < json.length()) {
                char character = json.charAt(position);
                if (character != ' ' && character != '\n' && character != '\r'
                        && character != '\t') {
                    return;
                }
                position++;
            }
        }

        private void expect(char expected) {
            if (position >= json.length() || json.charAt(position) != expected) {
                throw new IllegalArgumentException("Input JSON non valido");
            }
            position++;
        }
    }
}
