package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.ApiResponseDto;
import com.fun90.airopscat.model.dto.SystemRequestLogQuery;
import com.fun90.airopscat.service.SystemRequestLogService;
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

import java.time.LocalDateTime;

@ApplicationScoped
@Path("/api/admin/system-request-logs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SystemRequestLogController {

    @Inject
    SystemRequestLogService systemRequestLogService;

    @GET
    public Response list(@QueryParam("startTime") String startTime,
                         @QueryParam("endTime") String endTime,
                         @QueryParam("requestPath") String requestPath,
                         @QueryParam("clientIp") String clientIp,
                         @QueryParam("page") @DefaultValue("1") int page,
                         @QueryParam("size") @DefaultValue("20") int size) {
        SystemRequestLogQuery query = buildQuery(startTime, endTime, requestPath, clientIp);
        return Response.ok(ApiResponseDto.success(systemRequestLogService.getPage(query, page, size))).build();
    }

    @GET
    @Path("/stats")
    public Response stats(@QueryParam("startTime") String startTime,
                          @QueryParam("endTime") String endTime,
                          @QueryParam("requestPath") String requestPath,
                          @QueryParam("clientIp") String clientIp) {
        SystemRequestLogQuery query = buildQuery(startTime, endTime, requestPath, clientIp);
        return Response.ok(ApiResponseDto.success(systemRequestLogService.getStats(query))).build();
    }

    private SystemRequestLogQuery buildQuery(String startTime, String endTime, String requestPath, String clientIp) {
        return new SystemRequestLogQuery(
                parseDateTime(startTime),
                parseDateTime(endTime),
                requestPath,
                clientIp
        );
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace(' ', 'T');
        if (normalized.length() == 16) {
            normalized = normalized + ":00";
        }
        return LocalDateTime.parse(normalized);
    }
}
