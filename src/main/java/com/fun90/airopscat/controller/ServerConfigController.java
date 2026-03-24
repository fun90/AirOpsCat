package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.convert.ServerConfigConverter;
import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.ServerConfigDto;
import com.fun90.airopscat.model.dto.ServerConfigRequest;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.model.enums.CoreType;
import com.fun90.airopscat.service.ServerConfigService;
import com.fun90.airopscat.service.ServerHostService;
import com.fun90.airopscat.service.ServerService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
@Path("/api/admin/server-configs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServerConfigController {

    @Inject
    ServerConfigService serverConfigService;
    
    @Inject
    ServerService serverService;

    @Inject
    ServerHostService serverHostService;

    @GET
    public Response getServerConfigPage(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("search") String search,
            @QueryParam("configType") String configType
    ) {
        PanacheQuery<ServerConfig> configQuery = serverConfigService.getServerConfigPage(search, configType);
        configQuery.page(Page.of(page - 1, size));
        
        // Convert to DTOs
        List<ServerConfigDto> configDtos = configQuery.list().stream()
                .map(ServerConfigConverter::toDto)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", configDtos);
        response.put("total", configQuery.count());
        response.put("pages", configQuery.pageCount());
        response.put("current", page);
        response.put("size", size);
        
        // Add statistics
        response.put("stats", serverConfigService.getServerConfigStats());

        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}")
    public Response getServerConfigById(@PathParam("id") Long id) {
        ServerConfig serverConfig = serverConfigService.getServerConfigById(id);
        if (serverConfig != null) {
            ServerConfigDto dto = ServerConfigConverter.toDto(serverConfig);
            return Response.ok(dto).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    @GET
    @Path("/server/{serverId}")
    public Response getServerConfigsByServer(@PathParam("serverId") Long serverId) {
        List<ServerConfig> configs = serverConfigService.getServerConfigsByServerId(serverId);
        List<ServerConfigDto> configDtos = configs.stream()
                .map(ServerConfigConverter::toDto)
                .collect(Collectors.toList());
        return Response.ok(configDtos).build();
    }
    
    @GET
    @Path("/types")
    public Response getConfigTypes() {
        List<Map<String, String>> types = Stream.of(CoreType.values())
                .map(type -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("value", type.getValue());
                    map.put("label", type.getName());
                    return map;
                })
                .collect(Collectors.toList());

        return Response.ok(types).build();
    }
    
    @GET
    @Path("/stats")
    public Response getServerConfigStats() {
        return Response.ok(serverConfigService.getServerConfigStats()).build();
    }

    @POST
    public Response createServerConfig(ServerConfigRequest request) {
        try {
            // 创建ServerConfig实体
            ServerConfig serverConfig = new ServerConfig();
            serverConfig.setServerId(request.getServerId());
            serverConfig.setConfig(request.getConfig());
            serverConfig.setConfigType(request.getConfigType());
            serverConfig.setPath(request.getPath());

            ServerConfig savedConfig = serverConfigService.saveServerConfig(serverConfig);
            
            return Response.ok(ServerConfigConverter.toDto(savedConfig)).build();
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    @PUT
    @Path("/{id}")
    public Response updateServerConfig(@PathParam("id") Long id, ServerConfigRequest request) {
        try {
            // 创建ServerConfig实体
            ServerConfig serverConfig = new ServerConfig();
            serverConfig.setId(id);
            serverConfig.setServerId(request.getServerId());
            serverConfig.setConfig(request.getConfig());
            serverConfig.setConfigType(request.getConfigType());
            serverConfig.setPath(request.getPath());

            ServerConfigDto updatedConfig = serverConfigService.updateServerConfig(serverConfig);

            return Response.ok(updatedConfig).build();
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deleteServerConfig(@PathParam("id") Long id) {
        ServerConfig existingConfig = serverConfigService.getServerConfigById(id);
        if (existingConfig == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        serverConfigService.deleteServerConfig(id);
        return Response.ok().build();
    }
    
    @POST
    @Path("/{id}/upload")
    public Response uploadConfigToServer(@PathParam("id") Long id) {
        try {
            CoreManagementResult result = serverConfigService.uploadConfigToServer(id);
            return Response.ok(result).build();
        } catch (Exception e) {
            CoreManagementResult errorResult = new CoreManagementResult();
            errorResult.setSuccess(false);
            errorResult.setMessage("上传失败: " + e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(errorResult).build();
        }
    }
    
    @GET
    @Path("/servers")
    public Response getServers() {
        List<Map<String, Object>> serverOptions = serverService.getAllActiveServers().stream()
                .map(server -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("id", server.getId());
                    option.put("name", (server.getName() != null ? server.getName() : "") +
                              " (" + server.getIp() + ")");
                    option.put("ip", server.getIp());
                    option.put("host", serverHostService.resolvePrimaryHost(server));
                    return option;
                })
                .collect(Collectors.toList());
        return Response.ok(serverOptions).build();
    }
}
