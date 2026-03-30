package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.ServerMonitorTrafficCalibrationDto;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.service.ServerMonitorStatsService;
import com.fun90.airopscat.service.ServerService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
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

    @ConfigProperty(name = "airopscat.server.monitor.refresh-minutes", defaultValue = "1")
    long monitorRefreshMinutes;

    @GET
    @Path("/{serverId}/summary")
    public Response getSummary(@PathParam("serverId") Long serverId) {
        Server server = serverService.getServerById(serverId);
        Response guardResponse = guardMonitorServer(server);
        if (guardResponse != null) {
            return guardResponse;
        }
        var summary = serverMonitorStatsService.getLatestSummary(server);
        summary.setMonitorIntervalSeconds(Math.max(1L, monitorRefreshMinutes) * 60L);
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

    @PUT
    @Path("/{serverId}/traffic-calibration")
    public Response calibrateTraffic(@PathParam("serverId") Long serverId, ServerMonitorTrafficCalibrationDto calibrationDto) {
        Server server = serverService.getServerById(serverId);
        Response guardResponse = guardMonitorServer(server);
        if (guardResponse != null) {
            return guardResponse;
        }

        if (calibrationDto == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "请求数据不能为空"))
                    .build();
        }
        if (calibrationDto.getUploadGb() == null || calibrationDto.getUploadGb().compareTo(BigDecimal.ZERO) < 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "累计上传流量必须大于或等于 0"))
                    .build();
        }
        if (calibrationDto.getDownloadGb() == null || calibrationDto.getDownloadGb().compareTo(BigDecimal.ZERO) < 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "累计下载流量必须大于或等于 0"))
                    .build();
        }

        var summary = serverMonitorStatsService.calibrateCurrentPeriod(server, calibrationDto);
        summary.setMonitorIntervalSeconds(Math.max(1L, monitorRefreshMinutes) * 60L);
        return Response.ok(summary).build();
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
}
