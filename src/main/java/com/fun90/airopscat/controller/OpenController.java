package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.guard.GuardSyncRequest;
import com.fun90.airopscat.model.dto.guard.GuardSyncResponse;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.service.AccountOnlineIpService;
import com.fun90.airopscat.service.AccountTrafficStatsService;
import com.fun90.airopscat.service.SubscriptionService;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.guard.AccountGuardAggregator;
import com.fun90.airopscat.service.guard.AccountGuardEvaluation;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final int GUARD_SCHEMA_VERSION = 1;
    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Inject
    AccountOnlineIpService accountOnlineIpService;

    @Inject
    AccountTrafficStatsService accountTrafficStatsService;

    @Inject
    AccountGuardAggregator accountGuardAggregator;

    @Inject
    AccountRepository accountRepository;

    @Inject
    SubscriptionService subscriptionService;

    @Inject
    SystemConfigService systemConfigService;

    @GET
    @Path("/docs-info/{authCode}")
    public Response getDocsInfo(@PathParam("authCode") String authCode) {
        Optional<Account> accountOpt = findActiveAccount(authCode);
        if (accountOpt.isEmpty()) {
            return invalidAuthCodeResponse();
        }
        return Response.ok(buildDocsInfo(accountOpt.get())).build();
    }

    private Optional<Account> findActiveAccount(String authCode) {
        if (authCode == null || authCode.isBlank()) {
            return Optional.empty();
        }
        return accountRepository.findByAuthCode(authCode).filter(Account::isActive);
    }

    private Response invalidAuthCodeResponse() {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "无效的认证码，账户不存在"))
                .build();
    }

    private Map<String, Object> buildDocsInfo(Account account) {
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
        result.put("usage", buildUsage(account));
        return result;
    }

    /**
     * 当前周期流量与账户到期信息；totalBytes 为 null 表示不限量，expireDate 为 null 表示长期有效。
     */
    private Map<String, Object> buildUsage(Account account) {
        List<AccountTrafficStats> currentStats =
                accountTrafficStatsService.getStatsByAccountAndCurrentTime(account.getId(), LocalDateTime.now());
        AccountTrafficStats stats = currentStats.isEmpty() ? null : currentStats.getFirst();
        long usedBytes = stats == null ? 0L
                : Objects.requireNonNullElse(stats.getUploadBytes(), 0L) + Objects.requireNonNullElse(stats.getDownloadBytes(), 0L);
        Long quotaGb = accountTrafficStatsService.getEffectiveBandwidth(account.getId());

        Map<String, Object> usage = new HashMap<>();
        usage.put("usedBytes", usedBytes);
        usage.put("totalBytes", quotaGb == null ? null : quotaGb * BYTES_PER_GB);
        usage.put("resetDate", stats == null || stats.getPeriodEnd() == null ? null : stats.getPeriodEnd().format(DATE_FORMATTER));
        LocalDateTime toDate = account.getToDate();
        usage.put("expireDate", toDate == null ? null : toDate.format(DATE_FORMATTER));
        usage.put("remainingDays", toDate == null ? null
                : Math.max(0L, ChronoUnit.DAYS.between(LocalDate.now(), toDate.toLocalDate())));
        return usage;
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

    @POST
    @Path("/guard-sync")
    public Response guardSync(GuardSyncRequest request,
                              @HeaderParam("Token") String requestToken) {
        String expected = getApiToken();
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

        AccountGuardEvaluation evaluation;
        try {
            evaluation = accountGuardAggregator.reportAndEvaluateWithStats(request);
        } catch (Exception e) {
            log.warn("guard-sync 处理失败: nodeIp={}, error={}", request.getNodeIp(), e.getMessage(), e);
            evaluation = new AccountGuardEvaluation(Map.of(), Map.of());
        }
        try {
            if (request.getOnlineAccountIps() != null) {
                int refreshed = accountOnlineIpService.refreshFromGuardAccountIps(request.getNodeIp(), request.getOnlineAccountIps());
                log.debug("guard-sync 在线状态刷新完成: nodeIp={}, upsert={}", request.getNodeIp(), refreshed);
            }
        } catch (Exception e) {
            log.warn("guard-sync 在线状态刷新失败: nodeIp={}, error={}", request.getNodeIp(), e.getMessage(), e);
        }

        GuardSyncResponse response = new GuardSyncResponse();
        response.setSchemaVersion(GUARD_SCHEMA_VERSION);
        response.setGeneratedAtEpochSeconds(Instant.now().getEpochSecond());
        response.setTtlSeconds(accountGuardAggregator.getTtlSeconds());
        response.setBlockedAccounts(evaluation.getBlockedAccounts());
        return Response.ok(response).build();
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
