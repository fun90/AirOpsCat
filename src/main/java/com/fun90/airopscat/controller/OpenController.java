package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.ClientRequest;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.service.AccountOnlineIpService;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@ApplicationScoped
@Path("/api/open")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OpenController {

    @Inject
    AccountOnlineIpService accountOnlineIpService;

    @Inject
    AccountRepository accountRepository;

    @ConfigProperty(name = "airopscat.apple.id")
    String appleId;

    @ConfigProperty(name = "airopscat.apple.pwd")
    String applePwd;

    @ConfigProperty(name = "airopscat.subscription.url")
    String subscriptionUrl;

    @ConfigProperty(name = "airopscat.api.token")
    String apiToken;

    @POST
    @Path("/account/online/{nodeIp}")
    public Response access(ClientRequest request, @PathParam("nodeIp") String nodeIp, @HeaderParam("Token") String requestToken) {
        // 验证API Token
        if (!this.apiToken.equals(requestToken)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        accountOnlineIpService.updateOnlineStatus(request, nodeIp);
        return Response.ok().build();
    }

    @GET
    @Path("/docs-info/{authCode}")
    public Response getDocsInfo(@PathParam("authCode") String authCode) {
        // 查找账户
        Optional<Account> accountOpt = accountRepository.findByAuthCode(authCode);
        if (accountOpt.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "无效的认证码，账户不存在")).build();
        }
        // 组装subscriptionUrl
        String base = subscriptionUrl + "/config/" + authCode;
        Map<String, String> subscriptionUrls = Map.of(
            "windows", base + "/windows/clash-verge",
            "linux", base + "/linux/clash-verge",
            "ios", base + "/ios/shadowrocket",
            "macos", base + "/macos/clash-verge",
            "android", base + "/android/clash-meta"
        );
        Map<String, Object> result = new HashMap<>();
        result.put("subscriptionUrl", subscriptionUrls);
        result.put("appleId", appleId);
        result.put("applePwd", applePwd);
        result.put("nickName", accountOpt.get().getUser().getNickName());
        return Response.ok(result).build();
    }

    @GET
    @Path("/bark-test/{secretKey}")
    @Produces(MediaType.APPLICATION_JSON)
    public String barkTestGet(@PathParam("secretKey") String secretKey) {
        return createBarkTestResponse();
    }
    
    @POST
    @Path("/bark-test/{secretKey}")
    @Produces(MediaType.APPLICATION_JSON)
    public String barkTestPost(@PathParam("secretKey") String secretKey) {
        return createBarkTestResponse();
    }
    
    private String createBarkTestResponse() {
        log.info("收到Bark测试请求");
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

}
