package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.AcknowledgeRequest;
import com.fun90.airopscat.model.dto.AlertStatePageVo;
import com.fun90.airopscat.model.dto.AlertStateVo;
import com.fun90.airopscat.service.AlertStateService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@ApplicationScoped
@Path("/api/alert-states")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AlertStateController {

    @Inject
    AlertStateService alertStateService;

    @Inject
    SecurityIdentity securityIdentity;

    @GET
    public Response list(
            @QueryParam("alertType") String alertType,
            @QueryParam("status") String status,
            @QueryParam("resourceType") String resourceType,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        AlertStatePageVo result = alertStateService.getPage(alertType, status, resourceType, page, size);
        return Response.ok(Map.of(
                "records", result.getRecords(),
                "total", result.getTotal(),
                "pages", result.getPages(),
                "current", result.getCurrent(),
                "size", result.getSize(),
                "stats", alertStateService.getStats()
        )).build();
    }

    @GET
    @Path("/stats")
    public Response stats() {
        return Response.ok(alertStateService.getStats()).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") Long id) {
        AlertStateVo vo = alertStateService.getById(id);
        return Response.ok(vo).build();
    }

    @POST
    @Path("/{id}/acknowledge")
    public Response acknowledge(@PathParam("id") Long id, AcknowledgeRequest request) {
        String acknowledgedBy = securityIdentity.isAnonymous() ? "system" : securityIdentity.getPrincipal().getName();
        AlertStateVo vo = alertStateService.acknowledge(id, request, acknowledgedBy);
        return Response.ok(vo).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") Long id) {
        alertStateService.delete(id);
        return Response.noContent().build();
    }
}
