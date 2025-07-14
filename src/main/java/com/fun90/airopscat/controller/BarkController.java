package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.BarkNotificationDto;
import com.fun90.airopscat.service.BarkService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * Bark通知控制器
 * 提供REST API接口来发送Bark通知
 * 支持GET和POST两种请求方式
 * 参考Bark官方文档: https://github.com/Finb/Bark
 */
@ApplicationScoped
@Path("/api/admin/bark")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BarkController {

    @Inject
    BarkService barkService;

    /**
     * 发送简单通知（POST方式）
     *
     * @param title 通知标题
     * @param body  通知内容
     * @return 发送结果
     */
    @POST
    @Path("/notify")
    public Response sendNotification(
            @QueryParam("title") String title,
            @QueryParam("body") String body) {
        
        boolean success = barkService.sendNotification(title, body);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "通知发送成功" : "通知发送失败");
        response.put("method", "POST");
        
        return Response.ok(response).build();
    }

    /**
     * 发送自定义通知（POST方式）
     *
     * @param notification 通知对象
     * @return 发送结果
     */
    @POST
    @Path("/notify/custom")
    public Response sendCustomNotification(BarkNotificationDto notification) {
        
        boolean success = barkService.sendNotification(notification);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "通知发送成功" : "通知发送失败");
        response.put("method", "POST");
        
        return Response.ok(response).build();
    }

    /**
     * 发送GET请求通知
     *
     * @param notification 通知对象
     * @return 发送结果
     */
    @POST
    @Path("/notify/get")
    public Response sendNotificationGet(BarkNotificationDto notification) {
        
        boolean success = barkService.sendNotificationGet(notification);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "GET通知发送成功" : "GET通知发送失败");
        response.put("method", "GET");
        
        return Response.ok(response).build();
    }

    /**
     * 发送警告通知
     *
     * @param title 通知标题
     * @param body  通知内容
     * @return 发送结果
     */
    @POST
    @Path("/notify/warning")
    public Response sendWarningNotification(
            @QueryParam("title") String title,
            @QueryParam("body") String body) {
        
        boolean success = barkService.sendWarningNotification(title, body);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "警告通知发送成功" : "警告通知发送失败");
        response.put("method", "POST");
        
        return Response.ok(response).build();
    }

    /**
     * 发送错误通知
     *
     * @param title 通知标题
     * @param body  通知内容
     * @return 发送结果
     */
    @POST
    @Path("/notify/error")
    public Response sendErrorNotification(
            @QueryParam("title") String title,
            @QueryParam("body") String body) {
        
        boolean success = barkService.sendErrorNotification(title, body);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "错误通知发送成功" : "错误通知发送失败");
        response.put("method", "POST");
        
        return Response.ok(response).build();
    }

    /**
     * 发送信息通知
     *
     * @param title 通知标题
     * @param body  通知内容
     * @return 发送结果
     */
    @POST
    @Path("/notify/info")
    public Response sendInfoNotification(
            @QueryParam("title") String title,
            @QueryParam("body") String body) {
        
        boolean success = barkService.sendInfoNotification(title, body);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "信息通知发送成功" : "信息通知发送失败");
        response.put("method", "POST");
        
        return Response.ok(response).build();
    }

    /**
     * 获取Bark配置状态
     *
     * @return 配置状态
     */
    @GET
    @Path("/status")
    public Response getStatus() {
        boolean configured = barkService.isBarkConfigured();
        String barkUrl = barkService.getBarkUrl();
        String deviceKey = barkService.getDeviceKey();
        
        Map<String, Object> response = new HashMap<>();
        response.put("configured", configured);
        response.put("barkUrl", barkUrl);
        response.put("deviceKey", deviceKey != null ? deviceKey.substring(0, Math.min(8, deviceKey.length())) + "..." : null);
        response.put("message", configured ? "Bark已配置" : "Bark未配置");
        response.put("supportedMethods", new String[]{"GET", "POST"});
        
        return Response.ok(response).build();
    }

    /**
     * 测试Bark连接（GET方式）
     *
     * @return 测试结果
     */
    @POST
    @Path("/test")
    public Response testConnection() {
        boolean success = barkService.sendNotification("AirOpsCat测试", "这是一条测试通知");
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "Bark连接测试成功" : "Bark连接测试失败");
        response.put("method", "GET");
        
        return Response.ok(response).build();
    }

    /**
     * 测试Bark连接（POST方式）
     *
     * @return 测试结果
     */
    @POST
    @Path("/test/post")
    public Response testConnectionPost() {
        BarkNotificationDto notification = BarkNotificationDto.builder()
                .title("AirOpsCat测试")
                .body("这是一条POST测试通知")
                .build();
        
        boolean success = barkService.sendNotification(notification);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "Bark POST连接测试成功" : "Bark POST连接测试失败");
        response.put("method", "POST");
        
        return Response.ok(response).build();
    }

    /**
     * 测试Bark连接（GET方式）
     *
     * @return 测试结果
     */
    @POST
    @Path("/test/get")
    public Response testConnectionGet() {
        BarkNotificationDto notification = BarkNotificationDto.builder()
                .title("AirOpsCat测试")
                .body("这是一条GET测试通知")
                .build();
        
        boolean success = barkService.sendNotificationGet(notification);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "Bark GET连接测试成功" : "Bark GET连接测试失败");
        response.put("method", "GET");
        
        return Response.ok(response).build();
    }
} 