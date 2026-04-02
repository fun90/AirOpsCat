package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.ApiResponseDto;
import com.fun90.airopscat.model.dto.BarkConfigTestRequest;
import com.fun90.airopscat.model.dto.SystemConfigGroupDto;
import com.fun90.airopscat.model.dto.SystemConfigUpdateRequest;
import com.fun90.airopscat.service.BarkService;
import com.fun90.airopscat.service.SystemConfigService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@ApplicationScoped
@Path("/api/admin/system-configs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SystemConfigController {

    @Inject
    SystemConfigService systemConfigService;

    @Inject
    BarkService barkService;

    @GET
    public Response getGroups() {
        List<SystemConfigGroupDto> groups = systemConfigService.getConfigGroups();
        return Response.ok(ApiResponseDto.success(groups)).build();
    }

    @GET
    @Path("/{groupKey}")
    public Response getGroup(@PathParam("groupKey") String groupKey) {
        return Response.ok(ApiResponseDto.success(systemConfigService.getConfigGroup(groupKey))).build();
    }

    @PUT
    @Path("/{groupKey}")
    public Response saveGroup(@PathParam("groupKey") String groupKey, SystemConfigUpdateRequest request) {
        SystemConfigGroupDto group = systemConfigService.saveGroup(groupKey, request);
        return Response.ok(ApiResponseDto.success(group)).build();
    }

    @POST
    @Path("/bark/test")
    public Response testBark(BarkConfigTestRequest request) {
        String title = request.getTitle() == null || request.getTitle().isBlank()
                ? "AirOpsCat测试"
                : request.getTitle().trim();
        String body = request.getBody() == null || request.getBody().isBlank()
                ? "这是一条来自系统配置中心的测试通知。"
                : request.getBody().trim();

        boolean success = barkService.sendNotificationWithOverrides(title, body, request.getValues());
        if (!success) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponseDto.error("Bark测试通知发送失败"))
                    .build();
        }
        return Response.ok(ApiResponseDto.success("ok")).build();
    }
}
