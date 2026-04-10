package com.fun90.airopscat.service.ratelimit;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.scheduler.ScheduledSupport;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Slf4j
@ApplicationScoped
public class RateLimitService {

    private static final String REMOTE_RATE_LIMIT_DIR = "/etc/airopscat/ratelimit";
    private static final String REMOTE_RATE_LIMIT_PROFILE_PATH = REMOTE_RATE_LIMIT_DIR + "/accounts.json";

    @Inject
    ServerRepository serverRepository;

    @Inject
    AccountRepository accountRepository;

    @Inject
    ServerConfigRepository serverConfigRepository;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    SystemConfigService systemConfigService;

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

    public void syncServer(Server server) {
        if (server == null) {
            return;
        }
        if (!isEnabledSingBoxServer(server.getId())) {
            return;
        }

        syncProfileToServer(server, buildRateLimitProfileJson());
    }

    public void syncAll() {
        if (!isEnabled()) {
            log.info("全局限速开关已关闭，跳过限速配置同步");
            return;
        }

        List<Server> servers = findEnabledSingBoxServers();
        String profileJson = buildRateLimitProfileJson();
        Set<Long> singBoxServerIds = findEnabledSingBoxServerIds();
        CompletableFuture<?>[] futures = servers.stream()
                .filter(server -> singBoxServerIds.contains(server.getId()))
                .map(server -> CompletableFuture.runAsync(() -> {
                    runWithRequestContext(() -> {
                        try {
                            syncProfileToServer(server, profileJson);
                        } catch (Exception e) {
                            log.error("同步服务器限速配置失败, serverId={}", server.getId(), e);
                        }
                    });
                }, executorService))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
    }

    private void syncProfileToServer(Server server, String profileJson) {
        withConnection(server, connection -> {
            executeCommand(connection, "mkdir -p " + quoteShell(REMOTE_RATE_LIMIT_DIR), server.getId(), false);
            connection.writeRemoteFile(REMOTE_RATE_LIMIT_PROFILE_PATH, profileJson);
            log.info("已同步限速配置文件, serverId={}, path={}", server.getId(), REMOTE_RATE_LIMIT_PROFILE_PATH);
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

    private String buildRateLimitProfileJson() {
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (Account account : accountRepository.findActiveRateLimitedAccounts(LocalDateTime.now())) {
            if (account.getAccountNo() == null || account.getAccountNo().isBlank()) {
                continue;
            }
            Integer speed = account.getSpeed();
            if (speed == null || speed <= 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("accountNo", account.getAccountNo());
            item.put("speed", speed);
            accounts.add(item);
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("updatedAt", LocalDateTime.now());
        profile.put("accounts", accounts);
        return JsonUtil.toJsonString(profile);
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

    private String quoteShell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @FunctionalInterface
    private interface ConnectionConsumer {
        void accept(SshConnection connection) throws Exception;
    }
}
