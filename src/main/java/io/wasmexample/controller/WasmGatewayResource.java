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

@Path("/api/v1/promotion")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WasmGatewayResource {

    @Inject
    WasmExecutorService executorService;

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