package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.service.AccountTrafficStatsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;
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
            @QueryParam("startDate") String startDateStr,
            @QueryParam("endDate") String endDateStr,
            @QueryParam("sortBy") @DefaultValue("totalBytes") String sortBy,
            @QueryParam("sortDirection") @DefaultValue("desc") String sortDirection
    ) {
        LocalDateTime startDate = trafficStatsService.parseDateTime(startDateStr);
        LocalDateTime endDate = trafficStatsService.parseDateTime(endDateStr);

        Map<String, Object> response = trafficStatsService.getStatsListPage(
                search, startDate, endDate, page, size, sortBy, sortDirection);
        return Response.ok(response).build();
    }

    @GET
    @Path("/stats")
    public Response getStatsSummary(@QueryParam("search") String search,
                                    @QueryParam("startDate") String startDateStr,
                                    @QueryParam("endDate") String endDateStr) {
        LocalDateTime startDate = trafficStatsService.parseDateTime(startDateStr);
        LocalDateTime endDate = trafficStatsService.parseDateTime(endDateStr);
        Map<String, Object> response = trafficStatsService.getStatsSummary(search, startDate, endDate);
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
