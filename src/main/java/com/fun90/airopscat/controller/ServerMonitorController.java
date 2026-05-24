package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.service.ServerMonitorStatsService;
import com.fun90.airopscat.service.ServerService;
import com.fun90.airopscat.service.SystemConfigService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@ApplicationScoped
@Path("/api/admin/server-monitors")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServerMonitorController {

    @Inject
    ServerService serverService;

    @Inject
    ServerMonitorStatsService serverMonitorStatsService;

    @Inject
    SystemConfigService systemConfigService;

    @GET
    @Path("/{serverId}/summary")
    public Response getSummary(@PathParam("serverId") Long serverId) {
        Server server = serverService.getServerById(serverId);
        Response guardResponse = guardMonitorServer(server);
        if (guardResponse != null) {
            return guardResponse;
        }

        var summary = serverMonitorStatsService.getLatestSummary(server);
        summary.setMonitorIntervalSeconds(Math.max(1L, getRefreshMinutes()) * 60L);
        return Response.ok(summary).build();
    }

    @GET
    @Path("/{serverId}/charts")
    public Response getCharts(@PathParam("serverId") Long serverId,
                              @QueryParam("hours") @DefaultValue("24") int hours) {
        Server server = serverService.getServerById(serverId);
        Response guardResponse = guardMonitorServer(server);
        if (guardResponse != null) {
            return guardResponse;
        }
        return Response.ok(serverMonitorStatsService.getChartData(server, hours)).build();
    }

    @DELETE
    @Path("/{serverId}/records")
    public Response clearRecords(@PathParam("serverId") Long serverId) {
        Server server = serverService.getServerById(serverId);
        Response guardResponse = guardMonitorServer(server);
        if (guardResponse != null) {
            return guardResponse;
        }

        long deletedCount = serverMonitorStatsService.deleteByServerId(serverId);
        return Response.ok(Map.of(
                "serverId", serverId,
                "deletedCount", deletedCount
        )).build();
    }

    private Response guardMonitorServer(Server server) {
        if (server == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (server.getExternal() != null && server.getExternal() == 1) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "托管服务器不支持监控"))
                    .build();
        }
        return null;
    }

    private long getRefreshMinutes() {
        return systemConfigService.getLongValue("airopscat.server.monitor.refresh-minutes", 1L);
    }
}
