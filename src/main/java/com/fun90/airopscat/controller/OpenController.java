package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.ClientRequest;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.service.AccountOnlineIpService;
import com.fun90.airopscat.service.SubscriptionService;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@ApplicationScoped
@Path("/api/open")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OpenController {

    private static final String CONFIG_APPLE_ID = "airopscat.apple.id";
    private static final String CONFIG_APPLE_PWD = "airopscat.apple.pwd";
    private static final String CONFIG_API_TOKEN = "airopscat.api.token";

    @Inject
    AccountOnlineIpService accountOnlineIpService;

    @Inject
    AccountRepository accountRepository;

    @Inject
    SubscriptionService subscriptionService;

    @Inject
    SystemConfigService systemConfigService;

    /**
     * @deprecated 已由 Clash API 主动采集替代，此接口不再接受数据，返回 410 Gone。
     *             确认线上节点无旧上报脚本依赖后可删除此方法及 ClientRequest。
     */
    @Deprecated
    @POST
    @Path("/account/online/{nodeIp}")
    public Response access(List<ClientRequest> requests,
                           @PathParam("nodeIp") String nodeIp,
                           @HeaderParam("Token") String requestToken) {
        log.warn("已废弃的在线上报接口被调用: nodeIp={}, 请移除节点侧上报脚本", nodeIp);
        return Response.status(410)
                .entity(Map.of("message", "此接口已废弃，在线状态现由服务端主动采集，请移除节点侧上报脚本"))
                .build();
    }

    @GET
    @Path("/docs-info/{authCode}")
    public Response getDocsInfo(@PathParam("authCode") String authCode) {
        Optional<Account> accountOpt = accountRepository.findByAuthCode(authCode);
        if (accountOpt.isEmpty() || !accountOpt.get().isActive()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "无效的认证码，账户不存在"))
                    .build();
        }

        Account account = accountOpt.get();
        Map<String, String> subscriptionUrls = Map.of(
                "windows", subscriptionService.getConfigUrl(account, "windows", "clash-verge"),
                "linux", subscriptionService.getConfigUrl(account, "linux", "clash-verge"),
                "ios", subscriptionService.getConfigUrl(account, "ios", "shadowrocket"),
                "macos", subscriptionService.getConfigUrl(account, "macos", "clash-verge"),
                "android", subscriptionService.getConfigUrl(account, "android", "clash-meta")
        );

        Map<String, Object> result = new HashMap<>();
        result.put("subscriptionUrl", subscriptionUrls);
        result.put("appleId", getAppleId());
        result.put("applePwd", getApplePwd());
        result.put("nickName", account.getRemark());
        return Response.ok(result).build();
    }

    @GET
    @Path("/bark-test/{secretKey}")
    public String barkTestGet(@PathParam("secretKey") String secretKey) {
        return createBarkTestResponse();
    }

    @POST
    @Path("/bark-test/{secretKey}")
    public String barkTestPost(@PathParam("secretKey") String secretKey) {
        return createBarkTestResponse();
    }

    private String createBarkTestResponse() {
        log.info("收到 Bark 测试请求");
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("timestamp", System.currentTimeMillis());

        try {
            return JsonUtil.toJsonString(result);
        } catch (Exception e) {
            log.error("序列化响应失败", e);
            return "{\"code\":500,\"message\":\"Internal Server Error\"}";
        }
    }

    private String getAppleId() {
        return systemConfigService.getResolvedValue(CONFIG_APPLE_ID);
    }

    private String getApplePwd() {
        return systemConfigService.getResolvedValue(CONFIG_APPLE_PWD);
    }

    private String getApiToken() {
        return systemConfigService.getResolvedValue(CONFIG_API_TOKEN);
    }
}
