package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.DomainDnsRecordBatchRequest;
import com.fun90.airopscat.model.dto.DomainDnsRecordDto;
import com.fun90.airopscat.model.dto.DomainDnsRecordRequest;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.service.DomainDnsRecordService;
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
@Path("/api/admin/domains/{domainId}/dns-records")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DomainDnsRecordController {

    @Inject
    DomainDnsRecordService domainDnsRecordService;

    @GET
    public Response getPage(@PathParam("domainId") Long domainId,
                            @QueryParam("page") @DefaultValue("1") int page,
                            @QueryParam("size") @DefaultValue("10") int size,
                            @QueryParam("search") String search,
                            @QueryParam("type") String type,
                            @QueryParam("sortBy") String sortBy,
                            @QueryParam("sortOrder") @DefaultValue("asc") String sortOrder) {
        try {
            PanacheQuery<DomainDnsRecord> query = domainDnsRecordService.getPage(domainId, search, type, sortBy, sortOrder);
            query.page(Page.of(page - 1, size));
            List<DomainDnsRecordDto> records = query.list().stream()
                    .map(domainDnsRecordService::convertToDto)
                    .toList();

            Map<String, Object> response = new HashMap<>();
            response.put("records", records);
            response.put("total", query.count());
            response.put("pages", query.pageCount());
            response.put("current", page);
            response.put("size", size);
            return Response.ok(response).build();
        } catch (EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @POST
    public Response create(@PathParam("domainId") Long domainId, DomainDnsRecordRequest request) {
        try {
            return Response.ok(domainDnsRecordService.create(domainId, request)).build();
        } catch (EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{recordId}")
    public Response update(@PathParam("domainId") Long domainId,
                           @PathParam("recordId") Long recordId,
                           DomainDnsRecordRequest request) {
        try {
            return Response.ok(domainDnsRecordService.update(domainId, recordId, request)).build();
        } catch (EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{recordId}")
    public Response delete(@PathParam("domainId") Long domainId, @PathParam("recordId") Long recordId) {
        try {
            domainDnsRecordService.delete(domainId, recordId);
            return Response.ok().build();
        } catch (EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/batch")
    public Response batch(@PathParam("domainId") Long domainId, DomainDnsRecordBatchRequest request) {
        try {
            domainDnsRecordService.batch(domainId, request);
            return Response.ok(Map.of("message", "批量操作成功")).build();
        } catch (EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", e.getMessage())).build();
        }
    }
}
