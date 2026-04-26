package com.fun90.airopscat.service.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.AccountTrafficStatsRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.scheduler.ScheduledSupport;
import com.fun90.airopscat.service.AccountTrafficLimitService;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@ApplicationScoped
public class RateLimitService {

    private static final String REMOTE_RATE_LIMIT_DIR = "/etc/airopscat/ratelimit";
    private static final String REMOTE_RATE_LIMIT_PROFILE_PATH = REMOTE_RATE_LIMIT_DIR + "/accounts.json";
    private final Object syncAllLock = new Object();
    private final AtomicBoolean syncAllRunning = new AtomicBoolean(false);
    private final AtomicBoolean syncAllPending = new AtomicBoolean(false);
    private final AtomicLong syncAllSequence = new AtomicLong(0);

    @Inject
    ServerRepository serverRepository;

    @Inject
    AccountRepository accountRepository;

    @Inject
    AccountTrafficStatsRepository accountTrafficStatsRepository;

    @Inject
    ServerConfigRepository serverConfigRepository;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    SystemConfigService systemConfigService;

    @Inject
    AccountTrafficLimitService accountTrafficLimitService;

    @Inject
    ScheduledSupport scheduledSupport;

    @Inject
    @Named("monitorTaskExecutor")
    ExecutorService executorService;

    @Inject
    RequestContextController requestContextController;

    public boolean isEnabled() {
        return systemConfigService.getBooleanValue("airopscat.ratelimit.enabled", false);
    }

    @Transactional
    public void syncServer(Server server) {
        if (server == null) {
            return;
        }
        if (!isEnabledSingBoxServer(server.getId())) {
            return;
        }

        RateLimitSnapshot snapshot = createSnapshot();
        syncProfileToServer(server, buildRateLimitProfileJson(server, snapshot), snapshot.sequence());
    }

    public void syncAll() {
        syncAllPending.set(true);
        if (!syncAllRunning.compareAndSet(false, true)) {
            log.info("限速全量同步已在执行，当前请求已合并到下一轮");
            return;
        }

        try {
            while (true) {
                syncAllPending.set(false);
                doSyncAllOnce();
                if (!syncAllPending.get()) {
                    break;
                }
                log.info("检测到新的限速同步请求，继续执行下一轮全量同步");
            }
        } finally {
            syncAllRunning.set(false);
            if (syncAllPending.get() && syncAllRunning.compareAndSet(false, true)) {
                try {
                    while (true) {
                        syncAllPending.set(false);
                        doSyncAllOnce();
                        if (!syncAllPending.get()) {
                            break;
                        }
                        log.info("收尾阶段检测到新的限速同步请求，继续执行下一轮全量同步");
                    }
                } finally {
                    syncAllRunning.set(false);
                }
            }
        }
    }

    private void doSyncAllOnce() {
        synchronized (syncAllLock) {
            if (!isEnabled()) {
                log.info("全局限速开关已关闭，跳过限速配置同步");
                return;
            }

            List<Server> servers = findEnabledSingBoxServers();
            Set<Long> singBoxServerIds = findEnabledSingBoxServerIds();
            if (servers.isEmpty() || singBoxServerIds.isEmpty()) {
                log.info("未找到启用 sing-box 的服务器，跳过限速配置同步");
                return;
            }

            RateLimitSnapshot snapshot = createSnapshot();
            log.info("开始执行限速全量同步, sequence={}, serverCount={}", snapshot.sequence(), servers.size());

            CompletableFuture<?>[] futures = servers.stream()
                    .filter(server -> singBoxServerIds.contains(server.getId()))
                    .map(server -> CompletableFuture.runAsync(() -> {
                        runWithRequestContext(() -> {
                            try {
                                syncProfileToServer(server, buildRateLimitProfileJson(server, snapshot), snapshot.sequence());
                            } catch (Exception e) {
                                log.error("同步服务器限速配置失败, sequence={}, serverId={}", snapshot.sequence(), server.getId(), e);
                            }
                        });
                    }, executorService))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join();
            log.info("限速全量同步完成, sequence={}", snapshot.sequence());
        }
    }

    private void syncProfileToServer(Server server, String profileJson, long sequence) {
        withConnection(server, connection -> {
            executeCommand(connection, "mkdir -p " + quoteShell(REMOTE_RATE_LIMIT_DIR), server.getId(), false);
            connection.writeRemoteFile(REMOTE_RATE_LIMIT_PROFILE_PATH, profileJson);
            log.info("已同步限速配置文件, sequence={}, serverId={}, path={}",
                    sequence, server.getId(), REMOTE_RATE_LIMIT_PROFILE_PATH);
        });
    }

    private void withConnection(Server server, ConnectionConsumer consumer) {
        try (SshConnection connection = sshConnectionService.createConnection(scheduledSupport.buildSshConfig(server))) {
            consumer.accept(connection);
        } catch (Exception e) {
            log.error("执行远端限速命令失败, serverId={}", server.getId(), e);
        }
    }

    private void executeCommand(SshConnection connection, String command, Long serverId, boolean ignoreFailure) throws IOException {
        log.info("执行限速命令, serverId={}, command={}", serverId, command);
        var result = connection.executeCommand(command);
        if (!result.isSuccess() && !ignoreFailure) {
            log.error("限速命令执行失败, serverId={}, stderr={}", serverId, result.getStderr());
        } else if (!result.isSuccess()) {
            log.error("限速初始化命令执行失败, serverId={}, stderr={}", serverId, result.getStderr());
        }
    }

    private void runWithRequestContext(Runnable task) {
        boolean activated = requestContextController.activate();
        try {
            task.run();
        } finally {
            if (activated) {
                requestContextController.deactivate();
            }
        }
    }

    private String buildRateLimitProfileJson(Server server, RateLimitSnapshot snapshot) {
        Set<String> accountNos = extractServerAccountNos(server);
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (String accountNo : accountNos) {
            Account account = snapshot.accountMap().get(accountNo);
            if (account == null) {
                continue;
            }
            AccountTrafficLimitService.EffectiveSpeedLimit speedLimit = accountTrafficLimitService.resolveEffectiveSpeed(
                    account,
                    snapshot.currentStatsMap().get(account.getId()));
            if (speedLimit.speed() == null || speedLimit.speed() <= 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("accountNo", account.getAccountNo());
            item.put("speed", speedLimit.speed());
            accounts.add(item);
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("updatedAt", snapshot.updatedAt());
        profile.put("accounts", accounts);
        return JsonUtil.toJsonString(profile);
    }

    private RateLimitSnapshot createSnapshot() {
        LocalDateTime snapshotTime = LocalDateTime.now();
        Map<String, Account> accountMap = new LinkedHashMap<>();
        List<Account> accounts = accountRepository.findActiveAccounts(snapshotTime);
        for (Account account : accounts) {
            if (account.getAccountNo() == null || account.getAccountNo().isBlank()) {
                continue;
            }
            accountMap.put(account.getAccountNo(), account);
        }
        List<Long> accountIds = accounts.stream()
                .map(Account::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, AccountTrafficStats> currentStatsMap =
                accountTrafficStatsRepository.findCurrentPeriodByAccountIds(accountIds, snapshotTime);
        return new RateLimitSnapshot(syncAllSequence.incrementAndGet(), snapshotTime, accountMap, currentStatsMap);
    }

    private Set<String> extractServerAccountNos(Server server) {
        if (server == null || server.getId() == null) {
            return Set.of();
        }

        Set<String> accountNos = new LinkedHashSet<>();
        for (ServerConfig serverConfig : serverConfigRepository.findByServerId(server.getId())) {
            if (!isEnabledSingBoxConfig(serverConfig) || serverConfig.getConfig() == null || serverConfig.getConfig().isBlank()) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(serverConfig.getConfig());
                for (JsonNode inbound : root.path("inbounds")) {
                    JsonNode users = inbound.path("users");
                    if (!users.isArray()) {
                        continue;
                    }
                    for (JsonNode user : users) {
                        String accountNo = user.path("name").asText(null);
                        if (accountNo != null && !accountNo.isBlank()) {
                            accountNos.add(accountNo.trim());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("解析服务器 {} 的 sing-box 配置失败，跳过限速账号提取", server.getId(), e);
            }
        }
        return accountNos;
    }

    private List<Server> findEnabledSingBoxServers() {
        Set<Long> singBoxServerIds = findEnabledSingBoxServerIds();
        if (singBoxServerIds.isEmpty()) {
            return List.of();
        }
        return serverRepository.findMonitorableServers(LocalDate.now()).stream()
                .filter(server -> singBoxServerIds.contains(server.getId()))
                .toList();
    }

    private Set<Long> findEnabledSingBoxServerIds() {
        return serverConfigRepository.findEnabledServerIdsByConfigTypes(List.of("sing-box", "singbox"));
    }

    private boolean isEnabledSingBoxServer(Long serverId) {
        return serverId != null && findEnabledSingBoxServerIds().contains(serverId);
    }

    private boolean isEnabledSingBoxConfig(ServerConfig serverConfig) {
        if (serverConfig == null || serverConfig.getServerId() == null) {
            return false;
        }
        if (serverConfig.getEnabled() != null && serverConfig.getEnabled() == 0) {
            return false;
        }
        String configType = serverConfig.getConfigType();
        if (configType == null || configType.isBlank()) {
            return false;
        }
        String normalized = configType.trim().toLowerCase();
        return "sing-box".equals(normalized) || "singbox".equals(normalized);
    }

    private String quoteShell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @FunctionalInterface
    private interface ConnectionConsumer {
        void accept(SshConnection connection) throws Exception;
    }

    private record RateLimitSnapshot(long sequence,
                                     LocalDateTime updatedAt,
                                     Map<String, Account> accountMap,
                                     Map<Long, AccountTrafficStats> currentStatsMap) {
    }
}
