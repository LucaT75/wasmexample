package io.wasmexample.controller;

import java.util.Base64;

import static org.hamcrest.CoreMatchers.is;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.test.junit.QuarkusTest;
import static io.restassured.RestAssured.given;
import io.wasmexample.service.WasmExecutorService;
import jakarta.inject.Inject;

@QuarkusTest
public class WasmGatewayResourceTest {
    private static final Logger LOG = LoggerFactory.getLogger(WasmGatewayResourceTest.class);

    @Inject
    WasmExecutorService executorService;
    private static final String REAL_WASM_BINARY_BASE64 = 
        "AGFzbQEAAAABBgFgAX8BfwMCAQAHCwEHcHJvY2VzcwAACgkBBwAgAEECbAs=";

    @BeforeEach
    public void setupWasmModules() {
        // Decodifica la stringa direttamente in byte binari grezzi (come se arrivassero dal DB)
        byte[] wasmBinary = Base64.getDecoder().decode(REAL_WASM_BINARY_BASE64);
        
        // Carica il binario puro nel dizionario in memoria del servizio
        executorService.uploadCustomerService("moltiplicatore", wasmBinary);
    }


    @Test
    public void testDynamicWasmServiceExecution() {
        // Chiamiamo il gateway chiedendo di eseguire il servizio del cliente con valore = 21
        // Il modulo WASM interno farà 21 * 2 = 42
        given()
          .when()
            .queryParam("value", 21)
            .get("/api/v1/promotion/moltiplicatore")
          .then()
            .statusCode(200)
            .body("service", is("moltiplicatore"))
            .body("result", is(42));
    }

    @Test
    public void testMissingServiceReturns404() {
        // Verifica che richiedendo un servizio non esistente risponda correttamente con un 404
        given()
          .when()
            .queryParam("value", 10)
            .get("/api/v1/promotion/promo-fantasma")
          .then()
            .statusCode(404)
            .body(is("Servizio cliente non trovato: promo-fantasma"));
    }
}