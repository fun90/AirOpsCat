package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.convert.NodeConverter;
import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.dto.RouteRuleRequest;
import com.fun90.airopscat.model.entity.RouteRule;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.service.NodeService;
import com.fun90.airopscat.service.RouteRuleService;
import com.fun90.airopscat.service.ServerService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
@Path("/api/admin/route-rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RouteRuleController {

    @Inject
    RouteRuleService routeRuleService;

    @Inject
    ServerService serverService;

    @Inject
    NodeService nodeService;

    @GET
    public Response getRouteRulePage(@QueryParam("page") @DefaultValue("1") int page,
                                     @QueryParam("size") @DefaultValue("10") int size,
                                     @QueryParam("search") String search,
                                     @QueryParam("coreType") String coreType,
                                     @QueryParam("enabled") Boolean enabled) {
        PanacheQuery<RouteRule> query = routeRuleService.getRouteRulePage(search, coreType, enabled);
        query.page(Page.of(page - 1, size));

        Map<String, Object> response = new HashMap<>();
        response.put("records", query.list().stream().map(routeRuleService::toDto).toList());
        response.put("total", query.count());
        response.put("pages", query.pageCount());
        response.put("current", page);
        response.put("size", size);
        response.put("stats", routeRuleService.getRouteRuleStats());
        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}")
    public Response getRouteRuleById(@PathParam("id") Long id) {
        RouteRule routeRule = routeRuleService.getRouteRuleById(id);
        if (routeRule == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(routeRuleService.toDto(routeRule)).build();
    }

    @GET
    @Path("/types")
    public Response getRouteRuleTypes() {
        return Response.ok(routeRuleService.getRouteRuleTypeOptions()).build();
    }

    @GET
    @Path("/core-types")
    public Response getCoreTypes() {
        return Response.ok(routeRuleService.getSupportedCoreTypeOptions()).build();
    }

    @GET
    @Path("/servers")
    public Response getServers() {
        List<Map<String, Object>> serverOptions = serverService.getAllActiveServers().stream()
                .map(server -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("id", server.getId());
                    option.put("name", (server.getName() != null && !server.getName().isBlank() ? server.getName() : server.getIp())
                            + " (" + server.getIp() + ")");
                    option.put("ip", server.getIp());
                    option.put("host", server.getHost());
                    return option;
                })
                .collect(Collectors.toList());
        return Response.ok(serverOptions).build();
    }

    @GET
    @Path("/landing-nodes")
    public Response getLandingNodes(@QueryParam("coreType") String coreType) {
        List<NodeDto> nodes = nodeService.getNodeByType(NodeType.LANDING).stream()
                .filter(node -> node.getDisabled() == null || node.getDisabled() == 0)
                .filter(node -> coreType.equalsIgnoreCase(node.getCoreType()))
                .map(NodeConverter::toDto)
                .toList();
        return Response.ok(nodes).build();
    }

    @GET
    @Path("/stats")
    public Response getStats() {
        return Response.ok(routeRuleService.getRouteRuleStats()).build();
    }

    @POST
    public Response createRouteRule(RouteRuleRequest request) {
        try {
            return Response.ok(routeRuleService.saveRouteRule(request)).build();
        } catch (IllegalArgumentException | EntityNotFoundException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{id}")
    public Response updateRouteRule(@PathParam("id") Long id, RouteRuleRequest request) {
        try {
            return Response.ok(routeRuleService.updateRouteRule(id, request)).build();
        } catch (IllegalArgumentException | EntityNotFoundException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deleteRouteRule(@PathParam("id") Long id) {
        RouteRule routeRule = routeRuleService.getRouteRuleById(id);
        if (routeRule == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        routeRuleService.deleteRouteRule(id);
        return Response.ok().build();
    }

    @PATCH
    @Path("/{id}/enable")
    public Response enableRouteRule(@PathParam("id") Long id) {
        try {
            RouteRule routeRule = routeRuleService.toggleRouteRuleStatus(id, true);
            if (routeRule == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.ok(Map.of("id", id, "enabled", 1)).build();
        } catch (IllegalArgumentException | EntityNotFoundException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @PATCH
    @Path("/{id}/disable")
    public Response disableRouteRule(@PathParam("id") Long id) {
        RouteRule routeRule = routeRuleService.toggleRouteRuleStatus(id, false);
        if (routeRule == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(Map.of("id", id, "enabled", 0)).build();
    }
}
