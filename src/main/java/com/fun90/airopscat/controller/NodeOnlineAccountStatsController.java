package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.NodeOnlineAccountOverviewChartDto;
import com.fun90.airopscat.model.dto.NodeOnlineAccountOverviewSummaryDto;
import com.fun90.airopscat.model.dto.NodeOnlineAccountStatsChartDto;
import com.fun90.airopscat.model.dto.NodeOnlineAccountStatsSummaryDto;
import com.fun90.airopscat.service.NodeOnlineAccountStatsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/api/admin/node-online-account-stats")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NodeOnlineAccountStatsController {

    @Inject
    NodeOnlineAccountStatsService nodeOnlineAccountStatsService;

    @GET
    @Path("/overview/summary")
    public Response getOverviewSummary(@QueryParam("days") @DefaultValue("30") int days) {
        NodeOnlineAccountOverviewSummaryDto summary = nodeOnlineAccountStatsService.getOverviewSummary(days);
        return Response.ok(summary).build();
    }

    @GET
    @Path("/overview/charts")
    public Response getOverviewCharts(@QueryParam("days") @DefaultValue("30") int days) {
        NodeOnlineAccountOverviewChartDto chartData = nodeOnlineAccountStatsService.getOverviewChartData(days);
        return Response.ok(chartData).build();
    }

    @GET
    @Path("/{nodeId}/summary")
    public Response getSummary(@PathParam("nodeId") Long nodeId,
                               @QueryParam("days") @DefaultValue("30") int days) {
        NodeOnlineAccountStatsSummaryDto summary = nodeOnlineAccountStatsService.getSummary(nodeId, days);
        if (summary == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(summary).build();
    }

    @GET
    @Path("/{nodeId}/charts")
    public Response getCharts(@PathParam("nodeId") Long nodeId,
                              @QueryParam("days") @DefaultValue("30") int days) {
        NodeOnlineAccountStatsChartDto chartData = nodeOnlineAccountStatsService.getChartData(nodeId, days);
        if (chartData == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(chartData).build();
    }
}
