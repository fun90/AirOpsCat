package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.DnsProviderConfigDto;
import com.fun90.airopscat.model.dto.DnsProviderConfigRequest;
import com.fun90.airopscat.model.dto.DnsProviderTestResponse;
import com.fun90.airopscat.model.dto.DomainDto;
import com.fun90.airopscat.model.entity.DnsProviderConfig;
import com.fun90.airopscat.model.enums.DnsProviderConfigStatus;
import com.fun90.airopscat.model.enums.DnsProviderType;
import com.fun90.airopscat.service.DnsProviderConfigService;
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

@ApplicationScoped
@Path("/api/admin/dns-provider-configs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DnsProviderConfigController {

    @Inject
    DnsProviderConfigService dnsProviderConfigService;

    @GET
    public Response getPage(@QueryParam("page") @DefaultValue("1") int page,
                            @QueryParam("size") @DefaultValue("10") int size,
                            @QueryParam("search") String search,
                            @QueryParam("providerType") DnsProviderType providerType,
                            @QueryParam("status") DnsProviderConfigStatus status) {
        PanacheQuery<DnsProviderConfig> query = dnsProviderConfigService.getPage(search, providerType, status);
        query.page(Page.of(page - 1, size));

        List<DnsProviderConfigDto> records = query.list().stream()
                .map(dnsProviderConfigService::convertToDto)
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("records", records);
        response.put("total", query.count());
        response.put("pages", query.pageCount());
        response.put("current", page);
        response.put("size", size);
        response.put("stats", dnsProviderConfigService.getStats());
        return Response.ok(response).build();
    }

    @GET
    @Path("/enabled")
    public Response getEnabledConfigs() {
        return Response.ok(dnsProviderConfigService.getEnabledConfigs()).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") Long id) {
        DnsProviderConfig config = dnsProviderConfigService.getById(id);
        if (config == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(dnsProviderConfigService.convertToDto(config)).build();
    }

    @GET
    @Path("/{id}/domains")
    public Response getBoundDomains(@PathParam("id") Long id) {
        if (dnsProviderConfigService.getById(id) == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        List<DomainDto> domains = dnsProviderConfigService.getBoundDomains(id);
        return Response.ok(domains).build();
    }

    @POST
    public Response create(DnsProviderConfigRequest request) {
        try {
            DnsProviderConfig saved = dnsProviderConfigService.save(request);
            return Response.ok(dnsProviderConfigService.convertToDto(saved)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") Long id, DnsProviderConfigRequest request) {
        try {
            DnsProviderConfig updated = dnsProviderConfigService.update(id, request);
            return Response.ok(dnsProviderConfigService.convertToDto(updated)).build();
        } catch (EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") Long id) {
        try {
            dnsProviderConfigService.delete(id);
            return Response.ok().build();
        } catch (EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @PATCH
    @Path("/{id}/enable")
    public Response enable(@PathParam("id") Long id) {
        return toggleStatus(id, DnsProviderConfigStatus.ENABLED);
    }

    @PATCH
    @Path("/{id}/disable")
    public Response disable(@PathParam("id") Long id) {
        return toggleStatus(id, DnsProviderConfigStatus.DISABLED);
    }

    @POST
    @Path("/{id}/test")
    public Response testConnection(@PathParam("id") Long id) {
        try {
            DnsProviderTestResponse result = dnsProviderConfigService.testConnection(id);
            return Response.ok(result).build();
        } catch (EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", e.getMessage())).build();
        }
    }

    private Response toggleStatus(Long id, DnsProviderConfigStatus status) {
        try {
            DnsProviderConfig config = dnsProviderConfigService.toggleStatus(id, status);
            return Response.ok(Map.of("id", id, "status", config.getStatus())).build();
        } catch (EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", e.getMessage())).build();
        }
    }
}
