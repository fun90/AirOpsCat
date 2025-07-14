package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.ServerDto;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.service.ServerService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
@Path("/api/admin/servers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServerController {
    
    @Inject
    ServerService serverService;

    @GET
    public Response getServerPage(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("search") String search,
            @QueryParam("supplier") String supplier,
            @QueryParam("expired") Boolean expired,
            @QueryParam("disabled") Boolean disabled
    ) {
        PanacheQuery<Server> serverQuery = serverService.getServerPage(search, supplier, expired, disabled);
        serverQuery.page(Page.of(page - 1, size));
        
        // Convert to DTOs
        List<ServerDto> serverDtos = serverQuery.list().stream()
                .map(server -> serverService.convertToDto(server))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", serverDtos);
        response.put("total", serverQuery.count());
        response.put("pages", serverQuery.pageCount());
        response.put("current", page);
        response.put("size", size);
        
        // Add statistics
        response.put("stats", serverService.getServersStats());
        response.put("supplierStats", serverService.getServersBySupplier());
        response.put("totalCost", serverService.getTotalServerCost());
        response.put("totalEffectiveCost", serverService.getTotalEffectiveServerCost());

        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}")
    public Response getServerById(@PathParam("id") Long id) {
        Server server = serverService.getServerById(id);
        if (server != null) {
            ServerDto dto = serverService.convertToDto(server);
            return Response.ok(dto).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    @GET
    @Path("/suppliers")
    public Response getServersBySupplier() {
        return Response.ok(serverService.getServersBySupplier()).build();
    }
    
    @GET
    @Path("/auth-types")
    public Response getAuthTypes() {
        return Response.ok(serverService.getAuthTypeOptions()).build();
    }
    
    @GET
    @Path("/stats")
    public Response getServersStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.putAll(serverService.getServersStats());
        stats.put("supplierStats", serverService.getServersBySupplier());
        stats.put("totalCost", serverService.getTotalServerCost());
        stats.put("totalEffectiveCost", serverService.getTotalEffectiveServerCost());
        return Response.ok(stats).build();
    }
    
    @POST
    @Path("/test-connection")
    public Response testConnection(ServerDto server) {
        boolean success = serverService.testConnection(server);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "连接成功" : "连接失败");
        
        return Response.ok(response).build();
    }

    @POST
    public Response createServer(Server server) {
        Server savedServer = serverService.saveServer(server);
        return Response.ok(savedServer).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateServer(@PathParam("id") Long id, Server server) {
        Server existingServer = serverService.getServerById(id);
        if (existingServer == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        server.setId(id);
        Server updatedServer = serverService.updateServer(server);
        ServerDto dto = serverService.convertToDto(updatedServer);
        return Response.ok(dto).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteServer(@PathParam("id") Long id) {
        Server existingServer = serverService.getServerById(id);
        if (existingServer == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        serverService.deleteServer(id);
        return Response.ok().build();
    }

    @PATCH
    @Path("/{id}/enable")
    public Response enableServer(@PathParam("id") Long id) {
        Server server = serverService.toggleServerStatus(id, false);
        if (server != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 0);
            return Response.ok(response).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @PATCH
    @Path("/{id}/disable")
    public Response disableServer(@PathParam("id") Long id) {
        Server server = serverService.toggleServerStatus(id, true);
        if (server != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 1);
            return Response.ok(response).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    @PATCH
    @Path("/{id}/renew")
    public Response renewServer(
            @PathParam("id") Long id, 
            @QueryParam("expiryDate") String expiryDateStr
    ) {
        LocalDate expiryDate = LocalDate.parse(expiryDateStr);
        Server server = serverService.renewServer(id, expiryDate);
        ServerDto dto = serverService.convertToDto(server);
        return Response.ok(dto).build();
    }
}