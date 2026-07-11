package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.guard.GuardBlockedEntry;
import com.fun90.airopscat.model.dto.guard.GuardSyncRequest;
import com.fun90.airopscat.model.dto.guard.GuardSyncResponse;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.guard.AccountGuardAggregator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 账户防共享「合并请求」端点：节点 agent 每 ~5 秒发一次 guard-sync，
 * 请求体带本节点各账户的实时连接/IP，响应体直接回该节点相关账户的全局配额结论
 * （黑名单）。上报与取回配额合并为一次往返，见
 * docs/account-max-ips-anti-sharing-plan.md §4.1。
 *
 * <p>鉴权沿用 OpenController 的 Token 请求头模式（airopscat.api.token），
 * 防止伪造上报篡改聚合结果。
 */
@Slf4j
@ApplicationScoped
@Path("/api/node")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GuardSyncController {

    private static final String CONFIG_API_TOKEN = "airopscat.api.token";
    private static final int SCHEMA_VERSION = 1;

    @Inject
    AccountGuardAggregator aggregator;

    @Inject
    SystemConfigService systemConfigService;

    @POST
    @Path("/guard-sync")
    public Response guardSync(GuardSyncRequest request,
                              @HeaderParam("Token") String requestToken) {
        String expected = systemConfigService.getResolvedValue(CONFIG_API_TOKEN);
        if (expected != null && !expected.isBlank() && !Objects.equals(expected, requestToken)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "无效的 Token"))
                    .build();
        }
        if (request == null || request.getNodeIp() == null || request.getNodeIp().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "缺少 nodeIp"))
                    .build();
        }

        Map<String, GuardBlockedEntry> blocked;
        try {
            blocked = aggregator.reportAndEvaluate(request);
        } catch (Exception e) {
            log.warn("guard-sync 处理失败: nodeIp={}, error={}", request.getNodeIp(), e.getMessage(), e);
            // 出错时返回空黑名单（fail-open），不影响节点放行
            blocked = Map.of();
        }

        GuardSyncResponse response = new GuardSyncResponse();
        response.setSchemaVersion(SCHEMA_VERSION);
        response.setGeneratedAtEpochSeconds(Instant.now().getEpochSecond());
        response.setTtlSeconds(aggregator.getTtlSeconds());
        response.setBlockedAccounts(blocked);
        return Response.ok(response).build();
    }
}
