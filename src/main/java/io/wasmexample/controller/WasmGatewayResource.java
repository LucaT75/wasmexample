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

@Path("/api/v1/plugin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WasmGatewayResource {

    @Inject
    WasmExecutorService executorService;

    @POST
    @Path("/{pluginName}")
    @Consumes({MediaType.APPLICATION_OCTET_STREAM, "application/wasm"})
    @Operation(summary = "Upload or replace a WASM plugin")
    @RequestBody(
            description = "Binary content of the WebAssembly module",
            required = true,
            content = {
                    @Content(mediaType = "application/wasm",
                            schema = @Schema(type = SchemaType.STRING, format = "binary")),
                    @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM,
                            schema = @Schema(type = SchemaType.STRING, format = "binary"))
            })
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Plugin uploaded"),
            @APIResponse(responseCode = "400", description = "The WASM file is empty")
    })
    public Response uploadPlugin(
            @PathParam("pluginName") String pluginName,
            byte[] wasmBinary) {
        if (wasmBinary == null || wasmBinary.length == 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Il file del plugin WASM non può essere vuoto").build();
        }

        executorService.uploadPlugin(pluginName, wasmBinary);
        return Response.noContent().build();
    }

    @POST
    @Path("/{pluginName}/{methodName}")
    @Operation(summary = "Execute a method exported by a WASM plugin")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Plugin method executed"),
            @APIResponse(responseCode = "400", description = "Invalid input"),
            @APIResponse(responseCode = "404", description = "Plugin not found"),
            @APIResponse(responseCode = "500", description = "Plugin execution failed")
    })
    public Response executePlugin(
            @PathParam("pluginName") String pluginName,
            @PathParam("methodName") String methodName,
            JsonNode input) {
        try {
            JsonNode output = executorService.executePlugin(pluginName, methodName, input);
            return Response.ok(new ExecutionResult(pluginName, methodName, output)).build();
        } catch (WasmExecutorService.PluginNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Errore durante l'esecuzione del plugin: " + e.getMessage()).build();
        }
    }

    public static class ExecutionResult {
        public String plugin;
        public String method;
        public JsonNode result;

        public ExecutionResult(String plugin, String method, JsonNode result) {
            this.plugin = plugin;
            this.method = method;
            this.result = result;
        }
    }
}