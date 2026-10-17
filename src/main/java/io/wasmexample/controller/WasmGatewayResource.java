package io.wasmexample.controller;

import io.wasmexample.service.WasmExecutorService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/promotion")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WasmGatewayResource {

    @Inject
    WasmExecutorService executorService;

    @GET
    @Path("/{serviceName}")
    public Response runClientService(
            @PathParam("serviceName") String serviceName, 
            @QueryParam("value") long value) {
        try {
            // Esegue il modulo WASM associato a quel "serviceName"
            long output = executorService.executeCustomerCode(serviceName, value);
            
            // Ritorna il risultato elaborato dal modulo WASM del cliente
            return Response.ok(new ExecutionResult(serviceName, output)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Errore durante l'esecuzione del codice client: " + e.getMessage()).build();
        }
    }

    public static class ExecutionResult {
        public String service;
        public long result;

        public ExecutionResult(String service, long result) {
            this.service = service;
            this.result = result;
        }
    }
}