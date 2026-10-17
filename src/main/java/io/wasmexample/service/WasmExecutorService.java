package io.wasmexample.service;

import java.io.ByteArrayInputStream;
import java.util.concurrent.ConcurrentHashMap;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class WasmExecutorService {

    // Cache per non ri-parsare i byte del modulo WASM a ogni singola chiamata REST
    private final ConcurrentHashMap<String, WasmModule> moduleCache = new ConcurrentHashMap();

    public long executeCustomerCode(String serviceName, long inputParam) {
        // 1. Controlla se il modulo WASM è già in cache, altrimenti leggilo dal DB
        WasmModule module = moduleCache.computeIfAbsent(serviceName, name -> {
            CustomerServiceEntity entity = CustomerServiceEntity.find("serviceName", name).firstResult();
            if (entity == null) {
                throw new IllegalArgumentException("Servizio cliente non trovato: " + name);
            }
            // Parsa l'array di byte direttamente dalla memoria
            return Parser.parse(new ByteArrayInputStream(entity.wasmBinary));
        });

        // 2. Crea una nuova istanza isolata (sandbox) per la richiesta corrente
        Instance instance = Instance.builder(module).build(); // 1.2.4

        // 3. Recupera la funzione esportata dal codice del cliente (es: "process_data")
        var function = instance.export("process_data"); // 1.2.4

        // 4. Esegui la logica passando il parametro e ricevi il risultato
        // Nota: I runtime WASM base lavorano nativamente con tipi numerici (i32, i64, f32, f64)
        long[] result = function.apply(inputParam); // 1.2.2, 1.2.4

        return result[0];
    }
    
    // Metodo da chiamare quando un cliente aggiorna il suo file .wasm per invalidare la cache
    public void invalidateCache(String serviceName) {
        moduleCache.remove(serviceName);
    }
}