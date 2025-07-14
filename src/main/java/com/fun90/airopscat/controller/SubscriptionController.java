package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.ApiResponseDto;
import com.fun90.airopscat.model.dto.SubscrptionDto;
import com.fun90.airopscat.service.SubscriptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Slf4j
@ApplicationScoped
@Path("/subscribe")
public class SubscriptionController {

    @Inject
    SubscriptionService subscriptionService;

    @GET
    @Path("/config/{authCode}/{osName}/{appName}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getSubscription(
            @PathParam("authCode") String authCode,
            @PathParam("osName") String osName,
            @PathParam("appName") String appName,
            @QueryParam("version") String version,
            @QueryParam("view") String view,
            @QueryParam("dns") String dns,
            @QueryParam("dns2") String dns2) {
        
        try {
            Map<String, String> params = new HashMap<>();
            if ("1".equals(version)) {
                if (Objects.nonNull(dns2)) {
                    params.put("dns", dns2);
                }
            } else {
                if (Objects.nonNull(dns)) {
                    params.put("dns", dns);
                }
            }
            ApiResponseDto<SubscrptionDto> response = subscriptionService.generateSubscription(authCode, osName, appName, params);
            if (!response.isSuccess()) {
                // 返回错误信息
                return Response.status(Response.Status.BAD_REQUEST)
                        .type(MediaType.TEXT_PLAIN)
                        .entity("错误: " + response.getMessage())
                        .build();
            }
            
            SubscrptionDto subscriptionDto = response.getData();
            String subscriptionContent = subscriptionDto.getContent();
            
            if (subscriptionContent == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .type(MediaType.TEXT_PLAIN)
                        .entity("错误: 生成配置文件失败")
                        .build();
            }

            Response.ResponseBuilder responseBuilder = Response.ok(subscriptionContent)
                    .type(MediaType.TEXT_PLAIN)
                    .header("charset", StandardCharsets.UTF_8.name())
                    .header("profile-update-interval", "72");
            
            if (!"1".equals(view)) {
                String fileName = URLEncoder.encode(subscriptionDto.getFileName(), StandardCharsets.UTF_8);
                responseBuilder.header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename*=UTF-8''" + fileName);
            }
            
            if ("shadowrocket".equalsIgnoreCase(appName)) {
                String usedFlow = new BigDecimal(subscriptionDto.getUsedFlow()).divide(new BigDecimal(1024 * 1024 * 1024), 2, RoundingMode.HALF_UP).toPlainString();
                String expireDate = subscriptionDto.getExpireDate().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
                responseBuilder.header("subscription-userinfo", "tfc: " + usedFlow + "G; exp: " + expireDate);
            } else {
                long expire = subscriptionDto.getExpireDate().toEpochSecond(ZoneOffset.of("+8"));
                responseBuilder.header("subscription-userinfo", "download=" + subscriptionDto.getUsedFlow()
                + "; total=" + subscriptionDto.getTotalFlow() + "; expire=" + expire);
            }
            // String docsIndex ="";
            // responseBuilder.header("profile-web-page-url", docsIndex + "?code=" +  subscription.getCode());

            return responseBuilder.build();
        } catch (Exception e) {
            log.error("Error generating subscription: {}", e.getMessage(), e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("错误: 系统内部错误，请联系管理员")
                    .build();
        }
    }

    @GET
    @Path("/rules/{appType}/{ruleName}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response rule(
        @PathParam("appType") String appType,
        @PathParam("ruleName") String ruleName) {
            String ruleContent = subscriptionService.getRule(appType, ruleName);
            return Response.ok(ruleContent)
                    .type(MediaType.TEXT_PLAIN)
                    .header("charset", StandardCharsets.UTF_8.name())
                    .build();
    }

    @GET
    @Path("/nodes/{authCode}/{appType}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response nodes(
        @PathParam("authCode") String authCode,
        @PathParam("appType") String appType) {
            String nodesContent = subscriptionService.getNodes(authCode, appType);
            return Response.ok(nodesContent)
                    .type(MediaType.TEXT_PLAIN)
                    .header("charset", StandardCharsets.UTF_8.name())
                    .build();
    }
} 