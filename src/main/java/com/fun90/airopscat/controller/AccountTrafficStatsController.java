package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.service.AccountTrafficStatsService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
@Path("/api/admin/traffic-stats")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccountTrafficStatsController {
    
    @Inject
    AccountTrafficStatsService trafficStatsService;

    @GET
    public Response getStatsPage(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("search") String search,
            @QueryParam("userId") Long userId,
            @QueryParam("accountId") Long accountId,
            @QueryParam("startDate") String startDateStr,
            @QueryParam("endDate") String endDateStr
    ) {
        LocalDateTime startDate = startDateStr != null ? LocalDateTime.parse(startDateStr) : null;
        LocalDateTime endDate = endDateStr != null ? LocalDateTime.parse(endDateStr) : null;
        
        PanacheQuery<AccountTrafficStats> statsQuery = trafficStatsService.getStatsPage(
                search, userId, accountId, startDate, endDate);
        statsQuery.page(Page.of(page - 1, size));

        Map<String, Object> response = new HashMap<>();
        response.put("records", statsQuery.list());
        response.put("total", statsQuery.count());
        response.put("pages", statsQuery.pageCount());
        response.put("current", page);
        response.put("size", size);

        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}")
    public Response getStatsById(@PathParam("id") Long id) {
        AccountTrafficStats stats = trafficStatsService.getStatsById(id);
        if (stats != null) {
            return Response.ok(stats).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    @GET
    @Path("/user/{userId}")
    public Response getStatsByUser(
            @PathParam("userId") Long userId,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size
    ) {
        PanacheQuery<AccountTrafficStats> statsQuery = trafficStatsService.getStatsPage(
                null, userId, null, null, null);
        statsQuery.page(Page.of(page - 1, size));
        
        Map<String, Object> response = new HashMap<>();
        response.put("records", statsQuery.list());
        response.put("total", statsQuery.count());
        response.put("pages", statsQuery.pageCount());
        response.put("current", page);
        response.put("size", size);
        
        // 添加总计流量数据
        response.put("totalUpload", trafficStatsService.getTotalUploadByUser(userId));
        response.put("totalDownload", trafficStatsService.getTotalDownloadByUser(userId));
        response.put("totalUploadFormatted", trafficStatsService.formatBytes(trafficStatsService.getTotalUploadByUser(userId)));
        response.put("totalDownloadFormatted", trafficStatsService.formatBytes(trafficStatsService.getTotalDownloadByUser(userId)));

        return Response.ok(response).build();
    }
    
    @GET
    @Path("/account/{accountId}")
    public Response getStatsByAccount(
            @PathParam("accountId") Long accountId,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size
    ) {
        PanacheQuery<AccountTrafficStats> statsQuery = trafficStatsService.getStatsPage(
                null, null, accountId, null, null);
        statsQuery.page(Page.of(page - 1, size));
        
        Map<String, Object> response = new HashMap<>();
        response.put("records", statsQuery.list());
        response.put("total", statsQuery.count());
        response.put("pages", statsQuery.pageCount());
        response.put("current", page);
        response.put("size", size);
        
        // 添加总计流量数据
        response.put("totalUpload", trafficStatsService.getTotalUploadByAccount(accountId));
        response.put("totalDownload", trafficStatsService.getTotalDownloadByAccount(accountId));
        response.put("totalUploadFormatted", trafficStatsService.formatBytes(trafficStatsService.getTotalUploadByAccount(accountId)));
        response.put("totalDownloadFormatted", trafficStatsService.formatBytes(trafficStatsService.getTotalDownloadByAccount(accountId)));

        return Response.ok(response).build();
    }

    @POST
    public Response createStats(AccountTrafficStats stats) {
        AccountTrafficStats savedStats = trafficStatsService.saveStats(stats);
        return Response.ok(savedStats).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateStats(@PathParam("id") Long id, AccountTrafficStats stats) {
        AccountTrafficStats existingStats = trafficStatsService.getStatsById(id);
        if (existingStats == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        stats.setId(id);
        AccountTrafficStats updatedStats = trafficStatsService.updateStats(stats);
        return Response.ok(updatedStats).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteStats(@PathParam("id") Long id) {
        AccountTrafficStats existingStats = trafficStatsService.getStatsById(id);
        if (existingStats == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        trafficStatsService.deleteStats(id);
        return Response.ok().build();
    }
}