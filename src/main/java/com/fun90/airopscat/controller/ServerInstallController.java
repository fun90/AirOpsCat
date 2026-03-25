package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.install.InstallScriptDto;
import com.fun90.airopscat.model.dto.install.ServerInstallExecuteRequest;
import com.fun90.airopscat.model.dto.install.ServerInstallStepResultDto;
import com.fun90.airopscat.service.ServerHostService;
import com.fun90.airopscat.service.ServerService;
import com.fun90.airopscat.service.install.ServerInstallService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/api/admin/server-installs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServerInstallController {

    @Inject
    ServerInstallService serverInstallService;

    @Inject
    ServerService serverService;

    @Inject
    ServerHostService serverHostService;

    @GET
    @Path("/scripts")
    public Response getScripts() {
        List<InstallScriptDto> scripts = serverInstallService.listScripts();
        Map<String, Object> response = new HashMap<>();
        response.put("records", scripts);
        response.put("total", scripts.size());
        return Response.ok(response).build();
    }

    @GET
    @Path("/servers")
    public Response getServers() {
        List<Map<String, Object>> serverOptions = serverService.getAllActiveServers().stream()
                // 只需要非托管的服务器
                .filter(o -> o.getExternal() == null || o.getExternal() == 0)
                .map(server -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("id", server.getId());
                    option.put("name", (server.getName() != null ? server.getName() : "未命名服务器") + " (" + server.getIp() + ")");
                    option.put("ip", server.getIp());
                    option.put("host", serverHostService.resolvePrimaryHost(server));
                    option.put("username", server.getUsername());
                    return option;
                })
                .toList();
        return Response.ok(serverOptions).build();
    }

    @GET
    @Path("/scripts/{scriptName}/preview")
    public Response previewScript(@jakarta.ws.rs.PathParam("scriptName") String scriptName) {
        if (scriptName == null || scriptName.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "脚本名称不能为空"))
                    .build();
        }

        try {
            String content = serverInstallService.getScriptContent(scriptName);
            Map<String, Object> response = new HashMap<>();
            response.put("scriptName", scriptName);
            response.put("content", content);
            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("message", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("message", "读取脚本失败: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/execute")
    public Response execute(ServerInstallExecuteRequest request) {
        if (request == null || request.getServerId() == null || request.getScriptName() == null || request.getScriptName().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "服务器和脚本不能为空"))
                    .build();
        }

        try {
            ServerInstallStepResultDto result = serverInstallService.executeScript(request.getServerId(), request.getScriptName());
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("message", "执行装机步骤失败: " + e.getMessage()))
                    .build();
        }
    }
}
