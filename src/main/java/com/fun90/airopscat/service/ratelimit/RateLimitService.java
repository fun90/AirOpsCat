package com.fun90.airopscat.service.ratelimit;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.dto.deployment.NodeClient;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.AccountTrafficStatsRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.scheduler.ScheduledSupport;
import com.fun90.airopscat.service.AccountTrafficLimitService;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.core.CoreManagementService;
import com.fun90.airopscat.service.deployment.DeploymentDataLoader;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.singbox.SingBoxConfigBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@ApplicationScoped
public class RateLimitService {

    private static final String CORE_TYPE_SING_BOX = "sing-box";
    private static final String SING_BOX_CONFIG_PATH = "/etc/sing-box/config.json";

    private final Object syncAllLock = new Object();
    private final AtomicBoolean syncAllRunning = new AtomicBoolean(false);
    private final AtomicBoolean syncAllPending = new AtomicBoolean(false);
    private final AtomicLong syncAllSequence = new AtomicLong(0);
    private final AtomicBoolean asyncSyncRunning = new AtomicBoolean(false);
    private final AtomicBoolean asyncSyncPending = new AtomicBoolean(false);

    @Inject
    ServerRepository serverRepository;

    @Inject
    AccountRepository accountRepository;

    @Inject
    AccountTrafficStatsRepository accountTrafficStatsRepository;

    @Inject
    ServerConfigRepository serverConfigRepository;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    SystemConfigService systemConfigService;

    @Inject
    AccountTrafficLimitService accountTrafficLimitService;

    @Inject
    DeploymentDataLoader deploymentDataLoader;

    @Inject
    SingBoxConfigBuilder singBoxConfigBuilder;

    @Inject
    CoreManagementService coreManagementService;

    @Inject
    ScheduledSupport scheduledSupport;

    @Inject
    @Named("monitorTaskExecutor")
    ExecutorService executorService;

    @Inject
    RequestContextController requestContextController;

    @Inject
    TransactionSynchronizationRegistry transactionSynchronizationRegistry;

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
        pushSingBoxConfig(server);
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
            if (servers.isEmpty()) {
                log.info("未找到启用 sing-box 的服务器，跳过限速配置同步");
                return;
            }

            long sequence = syncAllSequence.incrementAndGet();
            log.info("开始执行限速全量同步, sequence={}, serverCount={}", sequence, servers.size());

            CompletableFuture<?>[] futures = servers.stream()
                    .map(server -> CompletableFuture.runAsync(() -> {
                        runWithRequestContext(() -> {
                            try {
                                pushSingBoxConfig(server);
                            } catch (Exception e) {
                                log.error("同步服务器限速配置失败, sequence={}, serverId={}", sequence, server.getId(), e);
                            }
                        });
                    }, executorService))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join();
            log.info("限速全量同步完成, sequence={}", sequence);
        }
    }

    public Map<String, SingBoxConfigBuilder.RateLimitOverride> buildOverrides(DeploymentServerContext ctx) {
        RateLimitSnapshot snapshot = createSnapshot();
        return buildOverrides(ctx, snapshot);
    }

    private Map<String, SingBoxConfigBuilder.RateLimitOverride> buildOverrides(DeploymentServerContext ctx,
                                                                                RateLimitSnapshot snapshot) {
        Map<String, SingBoxConfigBuilder.RateLimitOverride> overrides = new LinkedHashMap<>();
        Set<String> accountNos = new LinkedHashSet<>();
        for (var nodeSnapshot : ctx.nodeSnapshotMap().values()) {
            for (NodeClient client : nodeSnapshot.clients()) {
                if (client.email() != null && !client.email().isBlank()) {
                    accountNos.add(client.email());
                }
            }
        }

        for (String accountNo : accountNos) {
            Account account = snapshot.accountMap().get(accountNo);
            if (account == null) {
                continue;
            }
            AccountTrafficStats stats = snapshot.currentStatsMap().get(account.getId());
            AccountTrafficLimitService.EffectiveMbpsLimit limit =
                    accountTrafficLimitService.resolveEffectiveMbps(account, stats);
            if (limit.downloadMbps() != null || limit.uploadMbps() != null) {
                overrides.put(accountNo, new SingBoxConfigBuilder.RateLimitOverride(
                        limit.downloadMbps(), limit.uploadMbps()));
            }
        }
        return overrides;
    }

    private void pushSingBoxConfig(Server server) {
        try {
            DeploymentServerContext ctx = deploymentDataLoader.loadForServer(server.getId());
            Map<String, SingBoxConfigBuilder.RateLimitOverride> overrides = buildOverrides(ctx);
            String config = singBoxConfigBuilder.build(ctx, ctx.nodes(), overrides);
            withConnection(server, connection -> {
                List<CoreManagementResult> results = coreManagementService.executeOperations(
                        CORE_TYPE_SING_BOX,
                        connection,
                        server.getIp(),
                        new CoreManagementService.OperationRequest(CoreOperation.CONFIG, config),
                        new CoreManagementService.OperationRequest(CoreOperation.RELOAD)
                );
                for (CoreManagementResult result : results) {
                    if (result != null && !result.isSuccess()) {
                        log.error("sing-box 限速操作失败, serverId={}, message={}", server.getId(), result.getMessage());
                    }
                }
                log.info("sing-box 限速配置推送成功, serverId={}", server.getId());
            });
        } catch (Exception e) {
            log.error("推送 sing-box 限速配置失败, serverId={}", server.getId(), e);
        }
    }

    private void withConnection(Server server, ConnectionConsumer consumer) {
        try (SshConnection connection = sshConnectionService.createConnection(scheduledSupport.buildSshConfig(server))) {
            consumer.accept(connection);
        } catch (Exception e) {
            log.error("执行远端限速命令失败, serverId={}", server.getId(), e);
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
                .filter(Objects::nonNull)
                .toList();
        Map<Long, AccountTrafficStats> currentStatsMap =
                accountTrafficStatsRepository.findCurrentPeriodByAccountIds(accountIds, snapshotTime);
        return new RateLimitSnapshot(syncAllSequence.get(), snapshotTime, accountMap, currentStatsMap);
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

    public void triggerAsyncSync() {
        if (transactionSynchronizationRegistry == null) {
            scheduleAsyncSync();
            return;
        }
        if (transactionSynchronizationRegistry.getTransactionKey() == null) {
            scheduleAsyncSync();
            return;
        }
        transactionSynchronizationRegistry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
            }

            @Override
            public void afterCompletion(int status) {
                if (status == Status.STATUS_COMMITTED) {
                    scheduleAsyncSync();
                }
            }
        });
    }

    private void scheduleAsyncSync() {
        asyncSyncPending.set(true);
        if (!asyncSyncRunning.compareAndSet(false, true)) {
            log.info("限速同步任务已在队列或执行中，本次请求已合并");
            return;
        }

        CompletableFuture.runAsync(() -> {
            boolean activated = requestContextController.activate();
            try {
                while (true) {
                    asyncSyncPending.set(false);
                    syncAll();
                    if (!asyncSyncPending.get()) {
                        break;
                    }
                    log.info("检测到新的限速同步请求，继续执行下一轮入口合并同步");
                }
            } catch (Exception e) {
                log.error("同步限速配置文件失败", e);
            } finally {
                if (activated) {
                    requestContextController.deactivate();
                }
                asyncSyncRunning.set(false);
                if (asyncSyncPending.get() && asyncSyncRunning.compareAndSet(false, true)) {
                    CompletableFuture.runAsync(this::runScheduledAsyncSync, executorService);
                }
            }
        }, executorService);
    }

    private void runScheduledAsyncSync() {
        boolean activated = requestContextController.activate();
        try {
            while (true) {
                asyncSyncPending.set(false);
                syncAll();
                if (!asyncSyncPending.get()) {
                    break;
                }
                log.info("收尾阶段检测到新的限速同步请求，继续执行下一轮入口合并同步");
            }
        } catch (Exception e) {
            log.error("同步限速配置文件失败", e);
        } finally {
            if (activated) {
                requestContextController.deactivate();
            }
            asyncSyncRunning.set(false);
            if (asyncSyncPending.get()) {
                scheduleAsyncSync();
            }
        }
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
