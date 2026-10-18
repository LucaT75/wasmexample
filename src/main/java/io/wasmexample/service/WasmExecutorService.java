package io.wasmexample.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ValType;

import io.wasmexample.repository.CustomerServiceEntity;
import io.wasmexample.repository.CustomerServiceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class WasmExecutorService {
    private static final Logger LOG = LoggerFactory.getLogger(WasmExecutorService.class);

    @Inject
    CustomerServiceRepository repository;

    @Inject
    ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, WasmModule> moduleCache = new ConcurrentHashMap<>();

    // Metodo utility per simulare il caricamento (upload) del modulo del cliente
    public void uploadCustomerService(String serviceName, byte[] wasmBinary) {         
        moduleCache.remove(serviceName); // Invalida la cache se esisteva
        repository.saveOrUpdate(serviceName, wasmBinary); // Salva o aggiorna il modulo nel DB
    }

    public JsonNode executeCustomerCode(String serviceName, String methodName, JsonNode input)
            throws IOException {
        WasmModule module = moduleCache.computeIfAbsent(serviceName, name -> {
            CustomerServiceEntity entity = repository.find("serviceName", name).firstResult();
            if (entity == null) {
                throw new CustomerServiceNotFoundException(
                        "Servizio cliente non trovato: " + name);
            }
            try {
                return Parser.parse(new ByteArrayInputStream(entity.wasmBinary));
            } catch (Exception e) {
                LOG.error("Errore durante il parsing del modulo WASM per il servizio: " + name, e);
                throw new IllegalStateException("Modulo WASM non valido per il servizio: " + name, e);
            }
        });

        validateFactorsInput(input);

        ImportValues imports = ImportValues.builder()
                .addFunction(new HostFunction("teavm", "currentTimeMillis",
                        List.of(), List.of(ValType.F64),
                        (instance, arguments) -> new long[] {
                                Double.doubleToRawLongBits(System.currentTimeMillis())
                        }))
                .addFunction(new HostFunction("teavm", "logString",
                        List.of(ValType.I32), List.of(),
                        (instance, arguments) -> {
                            LOG.debug("TeaVM WASM module logged a string at address {}",
                                    arguments[0]);
                            return new long[0];
                        }))
                .addFunction(new HostFunction("teavm", "logInt",
                        List.of(ValType.I32), List.of(),
                        (instance, arguments) -> {
                            LOG.debug("TeaVM WASM module logged value {}", arguments[0]);
                            return new long[0];
                        }))
                .addFunction(new HostFunction("teavm", "logOutOfMemory",
                        List.of(), List.of(),
                        (instance, arguments) -> {
                            LOG.error("TeaVM WASM module reported an out-of-memory error");
                            return new long[0];
                        }))
                .build();
        Instance instance = Instance.builder(module).withImportValues(imports).build();
        byte[] inputBytes = objectMapper.writeValueAsBytes(input);

        long[] allocation = instance.export("alloc").apply(inputBytes.length);
        if (allocation.length != 1) {
            throw new IllegalStateException("L'export WASM 'alloc' deve restituire un puntatore");
        }

        Memory memory = instance.memory();
        int inputPointer = checkedMemoryRange(
                allocation[0], inputBytes.length, memory, "area di input");
        memory.write(inputPointer, inputBytes);

        long[] result = instance.export(methodName).apply(inputPointer, inputBytes.length);
        if (result.length != 1) {
            throw new IllegalStateException(
                    "Il metodo WASM '" + methodName + "' deve restituire un valore i64");
        }

        long packedOutput = result[0];
        int outputPointer = (int) packedOutput;
        long outputLength = packedOutput >>> Integer.SIZE;
        int checkedOutputPointer = checkedMemoryRange(
                outputPointer, outputLength, memory, "area di output");
        byte[] outputBytes = memory.readBytes(checkedOutputPointer, (int) outputLength);
        JsonNode output = objectMapper.readTree(outputBytes);
        if (output == null) {
            throw new IllegalStateException(
                    "Il metodo WASM '" + methodName + "' ha restituito un output JSON vuoto");
        }
        return output;
    }

    private void validateFactorsInput(JsonNode input) {
        if (input == null || !input.isObject() || input.size() != 2
                || !isLong(input.get("factor1")) || !isLong(input.get("factor2"))) {
            throw new IllegalArgumentException(
                    "L'input deve contenere solo i campi interi factor1 e factor2");
        }
    }

    private boolean isLong(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong();
    }

    private int checkedMemoryRange(long pointer, long length, Memory memory, String description) {
        long unsignedPointer = Integer.toUnsignedLong((int) pointer);
        long memorySize = (long) memory.pages() * Memory.PAGE_SIZE;
        if (length < 0 || length > Integer.MAX_VALUE
                || unsignedPointer > Integer.MAX_VALUE
                || unsignedPointer + length > memorySize) {
            throw new IllegalStateException("Il modulo WASM ha restituito una " + description
                    + " non valida");
        }
        return (int) unsignedPointer;
    }

    // Metodo da chiamare quando un cliente aggiorna il suo file .wasm per invalidare la cache
    public void invalidateCache(String serviceName) {
        moduleCache.remove(serviceName);
    }

    public static class CustomerServiceNotFoundException extends IllegalArgumentException {
        public CustomerServiceNotFoundException(String message) {
            super(message);
        }
    }
}