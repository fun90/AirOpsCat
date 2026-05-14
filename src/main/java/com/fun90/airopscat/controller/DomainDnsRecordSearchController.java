package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.DomainDnsRecordDto;
import com.fun90.airopscat.repository.DomainDnsRecordRepository;
import com.fun90.airopscat.service.DomainDnsRecordService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/api/admin/domain-dns-records")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DomainDnsRecordSearchController {

    @Inject
    DomainDnsRecordRepository domainDnsRecordRepository;

    @Inject
    DomainDnsRecordService domainDnsRecordService;

    @GET
    public Response search(@QueryParam("search") String search,
                           @QueryParam("size") @DefaultValue("20") int size) {
        List<DomainDnsRecordDto> records = domainDnsRecordRepository.searchByFullName(search, size).stream()
                .map(domainDnsRecordService::convertToDto)
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("records", records);
        response.put("total", records.size());
        response.put("current", 1);
        response.put("size", size);
        response.put("pages", 1);
        return Response.ok(response).build();
    }
}
