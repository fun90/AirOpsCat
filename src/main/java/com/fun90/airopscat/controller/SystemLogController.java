package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.ApiResponseDto;
import com.fun90.airopscat.model.enums.SystemLogCategory;
import com.fun90.airopscat.service.SystemLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/api/admin/system-logs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SystemLogController {

    @Inject
    SystemLogService systemLogService;

    @GET
    public Response list(@QueryParam("category") String category,
                         @QueryParam("level") String level,
                         @QueryParam("keyword") String keyword,
                         @QueryParam("startTime") String startTime,
                         @QueryParam("endTime") String endTime,
                         @QueryParam("page") @DefaultValue("1") int page,
                         @QueryParam("size") @DefaultValue("20") int size) {
        try {
            return Response.ok(ApiResponseDto.success(systemLogService.getPage(
                    category, level, keyword, parseDateTime(startTime), parseDateTime(endTime), page, size))).build();
        } catch (java.time.format.DateTimeParseException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponseDto.error("时间格式不正确")).build();
        }
    }

    @GET
    @Path("/categories")
    public Response categories() {
        List<Map<String, String>> categories = Arrays.stream(SystemLogCategory.values())
                .map(item -> Map.of("value", item.name(), "label", item.getLabel()))
                .toList();
        return Response.ok(ApiResponseDto.success(categories)).build();
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
