package io.wasmexample.service;

import java.io.ByteArrayInputStream;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

import io.wasmexample.repository.CustomerServiceEntity;
import io.wasmexample.repository.CustomerServiceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class WasmExecutorService {
    private static final Logger LOG = LoggerFactory.getLogger(WasmExecutorService.class);

    @Inject
    CustomerServiceRepository repository;
    
    private final ConcurrentHashMap<String, WasmModule> moduleCache = new ConcurrentHashMap();

    // Metodo utility per simulare il caricamento (upload) del modulo del cliente
    public void uploadCustomerService(String serviceName, byte[] wasmBinary) {         
        moduleCache.remove(serviceName); // Invalida la cache se esisteva
        repository.saveOrUpdate(serviceName, wasmBinary); // Salva o aggiorna il modulo nel DB
    }

    public long executeCustomerCode(String serviceName, long inputParam) {
        // 1. Controlla se il modulo WASM è già in cache, altrimenti leggilo dal DB
        WasmModule module = moduleCache.computeIfAbsent(serviceName, name -> {
            CustomerServiceEntity entity = repository.find("serviceName", name).firstResult();
            if (entity == null) {
                throw new IllegalArgumentException("Servizio cliente non trovato: " + name);
            }
            // Parsa l'array di byte direttamente dalla memoria
            try {
                return Parser.parse(new ByteArrayInputStream(entity.wasmBinary));
            } catch (Exception e) {
                LOG.error("Errore durante il parsing del modulo WASM per il servizio: " + name, e);
                return null;
            }
        });

        // 2. Crea una nuova istanza isolata (sandbox) per la richiesta corrente
        Instance instance = Instance.builder(module).build(); // 1.2.4

        // 3. Recupera la funzione esportata dal codice del cliente (es: "process_data")
        var function = instance.export("process"); // 1.2.4

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