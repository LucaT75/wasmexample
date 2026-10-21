package io.wasmexample.controller;

import java.io.IOException;

import static org.hamcrest.CoreMatchers.is;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import static io.restassured.RestAssured.given;
import io.wasmexample.service.WasmExecutorService;
import io.wasmexample.service.WasmPluginValidator;
import jakarta.inject.Inject;

@QuarkusTest
public class WasmGatewayResourceTest {
    @Inject
    WasmExecutorService executorService;

    @BeforeEach
    public void setupWasmPlugins() throws IOException {
        byte[] multiplyWasmBinary = loadWasmResource("multiply.wasm");
        executorService.uploadPlugin("json-multiply", multiplyWasmBinary);
        byte[] sumWasmBinary = loadWasmResource("sum.wasm");
        executorService.uploadPlugin("json-sum", sumWasmBinary);
        executorService.uploadPlugin("json-diff", loadWasmResource("diff.wasm"));
    }

    private byte[] loadWasmResource(String name) throws IOException {
        try (var resource = getClass().getResourceAsStream("/wasm/" + name)) {
            if (resource == null) {
                throw new IOException("Risorsa WASM non trovata: " + name);
            }
            return resource.readAllBytes();
        }
    }

    @Test
    public void testUploadWasmThroughRestEndpoint() throws IOException {
      byte[] wasmBinary = loadWasmResource("multiply.wasm");

        given()
          .contentType("application/wasm")
          .body(wasmBinary)
          .when()
            .post("/api/v1/plugin/uploaded-multiply")
          .then()
            .statusCode(204);

        given()
          .contentType("application/json")
          .body("{\"factor1\":6,\"factor2\":7}")
          .when()
            .post("/api/v1/plugin/uploaded-multiply/multiply")
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
            .post("/api/v1/plugin/empty-plugin")
          .then()
            .statusCode(400)
            .body(is("Il file del plugin WASM non può essere vuoto"));
    }

    @Test
    public void testMalformedModuleIsRejectedWithoutDeactivatingCurrentPlugin() {
        given()
          .contentType("application/wasm")
          .body("not a wasm module".getBytes(java.nio.charset.StandardCharsets.UTF_8))
          .when()
            .post("/api/v1/plugin/json-multiply")
          .then()
            .statusCode(400);

        given()
          .contentType("application/json")
          .body("{\"factor1\":6,\"factor2\":7}")
          .when()
            .post("/api/v1/plugin/json-multiply/multiply")
          .then()
            .statusCode(200)
            .body("result.result", is(42));
    }

    @Test
    public void testZeroImportModuleExecutesVersionedJsonContract() throws IOException {
        given()
          .contentType("application/wasm")
          .body(loadWasmResource("multiply.wasm"))
          .when()
            .post("/api/v1/plugin/uploaded-multiply")
          .then()
            .statusCode(204);

        given()
          .contentType("application/json")
          .body("{\"factor1\":6,\"factor2\":7}")
          .when()
            .post("/api/v1/plugin/uploaded-multiply/multiply")
          .then()
            .statusCode(200)
            .body("result.result", is(42));
    }

    @Test
    public void testStoredPluginVersionCanBeReactivated() throws IOException {
        byte[] originalBinary = loadWasmResource("multiply.wasm");
        byte[] replacementBinary = loadWasmResource("sum.wasm");

        given()
          .contentType("application/wasm")
          .body(replacementBinary)
          .when()
            .post("/api/v1/plugin/json-multiply")
          .then()
            .statusCode(204);

        given()
          .when()
            .post("/api/v1/plugin/json-multiply/versions/"
                    + WasmPluginValidator.sha256Hex(originalBinary) + "/activate")
          .then()
            .statusCode(204);

        given()
          .contentType("application/json")
          .body("{\"factor1\":6,\"factor2\":7}")
          .when()
            .post("/api/v1/plugin/json-multiply/multiply")
          .then()
            .statusCode(200)
            .body("result.result", is(42));
    }

    @Test
    public void testMultiplyMethodUsesFactorsFromJsonInput() {
        given()
          .contentType("application/json")
          .body("{\"factor1\":6,\"factor2\":7}")
          .when()
            .post("/api/v1/plugin/json-multiply/multiply")
          .then()
            .statusCode(200)
            .body("plugin", is("json-multiply"))
            .body("method", is("multiply"))
            .body("result.result", is(42));
    }

    @Test
    public void testMultiplyUsesBothFactorsRegardlessOfFieldOrder() {
        given()
          .contentType("application/json")
          .body("{\"factor2\":-3,\"factor1\":9}")
          .when()
            .post("/api/v1/plugin/json-multiply/multiply")
          .then()
            .statusCode(200)
            .body("result.result", is(-27));
    }

    @Test
    public void testMultiplySupportsLargeLongResult() {
        given()
          .contentType("application/json")
          .body("{\"factor1\":900000000,\"factor2\":900000000}")
          .when()
            .post("/api/v1/plugin/json-multiply/multiply")
          .then()
            .statusCode(200)
            .body("result.result", is(810000000000000000L));
    }

    @Test
    public void testSumMethodUsesBothFactors() {
        given()
          .contentType("application/json")
          .body("{\"factor1\":6,\"factor2\":7}")
          .when()
            .post("/api/v1/plugin/json-sum/sum")
          .then()
            .statusCode(200)
            .body("plugin", is("json-sum"))
            .body("method", is("sum"))
            .body("result.result", is(13));
    }

    @Test
    public void testDiffMethodSubtractsSecondFactorFromFirst() {
        given()
          .contentType("application/json")
          .body("{\"factor1\":19,\"factor2\":7}")
          .when()
            .post("/api/v1/plugin/json-diff/diff")
          .then()
            .statusCode(200)
            .body("plugin", is("json-diff"))
            .body("result.result", is(12));

        given()
          .contentType("application/json")
          .body("{\"factor1\":7,\"factor2\":19}")
          .when()
            .post("/api/v1/plugin/json-diff/diff")
          .then()
            .statusCode(200)
            .body("result.result", is(-12));
    }

    @Test
    public void testMissingPluginReturns404() {
        given()
          .contentType("application/json")
          .body("{\"factor1\":6,\"factor2\":7}")
          .when()
            .post("/api/v1/plugin/promo-fantasma/multiply")
          .then()
            .statusCode(404)
            .body(is("Plugin non trovato: promo-fantasma"));
    }
}