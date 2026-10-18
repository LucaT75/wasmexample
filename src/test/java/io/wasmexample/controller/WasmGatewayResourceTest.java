package io.wasmexample.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.CoreMatchers.is;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import static io.restassured.RestAssured.given;
import io.wasmexample.service.WasmExecutorService;
import jakarta.inject.Inject;

@QuarkusTest
public class WasmGatewayResourceTest {
    @Inject
    WasmExecutorService executorService;

    @BeforeEach
    public void setupWasmModules() throws IOException {
        byte[] multiplyWasmBinary = Files.readAllBytes(Path.of("plugins", "target", "multiply.wasm"));
        executorService.uploadCustomerService("json-multiply", multiplyWasmBinary);
        byte[] sumWasmBinary = Files.readAllBytes(Path.of("plugins", "target", "sum.wasm"));
        executorService.uploadCustomerService("json-sum", sumWasmBinary);
    }

    @Test
    public void testUploadWasmThroughRestEndpoint() throws IOException {
        byte[] wasmBinary = Files.readAllBytes(Path.of("plugins", "target", "multiply.wasm"));

        given()
          .contentType("application/wasm")
          .body(wasmBinary)
          .when()
            .post("/api/v1/promotion/uploaded-multiply")
          .then()
            .statusCode(204);

        given()
          .contentType("application/json")
          .body("{\"factor1\":6,\"factor2\":7}")
          .when()
            .post("/api/v1/promotion/uploaded-multiply/multiply")
          .then()
            .statusCode(200)
            .body("result.result", is(42));
    }

    @Test
    public void testUploadEmptyWasmReturnsBadRequest() {
        given()
          .contentType("application/wasm")
          .body(new byte[0])
          .when()
            .post("/api/v1/promotion/empty-service")
          .then()
            .statusCode(400)
            .body(is("Il file WASM non può essere vuoto"));
    }

    @Test
    public void testMultiplyMethodUsesFactorsFromJsonInput() {
        given()
          .contentType("application/json")
          .body("{\"factor1\":6,\"factor2\":7}")
          .when()
            .post("/api/v1/promotion/json-multiply/multiply")
          .then()
            .statusCode(200)
            .body("service", is("json-multiply"))
            .body("method", is("multiply"))
            .body("result.result", is(42));
    }

    @Test
    public void testMultiplyUsesBothFactorsRegardlessOfFieldOrder() {
        given()
          .contentType("application/json")
          .body("{\"factor2\":-3,\"factor1\":9}")
          .when()
            .post("/api/v1/promotion/json-multiply/multiply")
          .then()
            .statusCode(200)
            .body("result.result", is(-27));
    }

    @Test
    public void testSumMethodUsesBothFactors() {
        given()
          .contentType("application/json")
          .body("{\"factor1\":6,\"factor2\":7}")
          .when()
            .post("/api/v1/promotion/json-sum/sum")
          .then()
            .statusCode(200)
            .body("service", is("json-sum"))
            .body("method", is("sum"))
            .body("result.result", is(13));
    }

    @Test
    public void testInputRequiresTwoIntegerFactors() {
        given()
          .contentType("application/json")
          .body("{\"factor1\":6,\"factor2\":\"7\"}")
          .when()
            .post("/api/v1/promotion/json-multiply/multiply")
          .then()
            .statusCode(400)
            .body(is("L'input deve contenere solo i campi interi factor1 e factor2"));
    }

    @Test
    public void testMissingServiceReturns404() {
        given()
          .contentType("application/json")
          .body("{\"factor1\":6,\"factor2\":7}")
          .when()
            .post("/api/v1/promotion/promo-fantasma/multiply")
          .then()
            .statusCode(404)
            .body(is("Servizio cliente non trovato: promo-fantasma"));
    }
}