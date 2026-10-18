package io.wasmexample.controller;

import io.wasmexample.service.WasmExecutorService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

@Path("/api/v1/promotion")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WasmGatewayResource {

    @Inject
    WasmExecutorService executorService;

    @POST
    @Path("/{serviceName}")
    @Consumes({MediaType.APPLICATION_OCTET_STREAM, "application/wasm"})
    @Operation(summary = "Carica o aggiorna un servizio WASM")
    @RequestBody(
            description = "Contenuto binario del modulo WebAssembly",
            required = true,
            content = {
                    @Content(mediaType = "application/wasm",
                            schema = @Schema(type = SchemaType.STRING, format = "binary")),
                    @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM,
                            schema = @Schema(type = SchemaType.STRING, format = "binary"))
            })
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Modulo caricato"),
            @APIResponse(responseCode = "400", description = "Il file WASM è vuoto")
    })
    public Response uploadCustomerService(
            @PathParam("serviceName") String serviceName,
            byte[] wasmBinary) {
        if (wasmBinary == null || wasmBinary.length == 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Il file WASM non può essere vuoto").build();
        }

        executorService.uploadCustomerService(serviceName, wasmBinary);
        return Response.noContent().build();
    }

    @POST
    @Path("/{serviceName}/{methodName}")
    public Response runClientService(
            @PathParam("serviceName") String serviceName,
            @PathParam("methodName") String methodName,
            JsonNode input) {
        try {
            JsonNode output = executorService.executeCustomerCode(serviceName, methodName, input);
            return Response.ok(new ExecutionResult(serviceName, methodName, output)).build();
        } catch (WasmExecutorService.CustomerServiceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Errore durante l'esecuzione del codice client: " + e.getMessage()).build();
        }
    }

    public static class ExecutionResult {
        public String service;
        public String method;
        public JsonNode result;

        public ExecutionResult(String service, String method, JsonNode result) {
            this.service = service;
            this.method = method;
            this.result = result;
        }
    }
}