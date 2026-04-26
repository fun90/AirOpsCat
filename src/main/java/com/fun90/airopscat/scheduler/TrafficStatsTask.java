package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.AccountTrafficOverQuotaService;
import com.fun90.airopscat.service.AccountTrafficStatsService;
import com.fun90.airopscat.service.BarkService;
import com.fun90.airopscat.service.ServerTrafficStatsService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.model.dto.UserTrafficStats;
import com.fun90.airopscat.singbox.SingBoxTrafficStatsCollector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class TrafficStatsTask {

    @Inject
    ServerConfigRepository serverConfigRepository;

    @Inject
    ServerRepository serverRepository;

    @Inject
    AccountRepository accountRepository;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    AccountTrafficStatsService accountTrafficStatsService;

    @Inject
    AccountTrafficOverQuotaService accountTrafficOverQuotaService;

    @Inject
    ServerTrafficStatsService serverTrafficStatsService;

    @Inject
    SingBoxTrafficStatsCollector singBoxTrafficStatsCollector;

    @Inject
    ScheduledSupport scheduledSupport;

    @Inject
    BarkService barkService;

    public void collectUserTrafficStats() {
        long startedAt = System.nanoTime();
        log.info("开始执行定时任务：收集用户流量统计");

        try {
            List<ServerConfig> serverConfigs = loadServerConfigs();
            if (serverConfigs.isEmpty()) {
                log.info("用户流量统计任务结束，没有找到可用的内核配置，耗时: {} ms",
                        elapsedMillis(startedAt));
                return;
            }

            log.info("找到 {} 个内核配置，支持的流量采集器: {}", serverConfigs.size(),
                    getRegisteredCollectorTypes());

            LocalDateTime now = LocalDateTime.now();
            int successCount = 0;
            int skippedCount = 0;
            int failureCount = 0;
            int processedServerCount = 0;

            Map<Long, List<ServerConfig>> serverConfigsByServerId = new LinkedHashMap<>();
            for (ServerConfig serverConfig : serverConfigs) {
                if (serverConfig.getServerId() == null) {
                    log.debug("配置 {} 未关联服务器，跳过流量统计", serverConfig.getId());
                    skippedCount++;
                    continue;
                }
                serverConfigsByServerId.computeIfAbsent(serverConfig.getServerId(), ignored -> new ArrayList<>())
                        .add(serverConfig);
            }

            for (Map.Entry<Long, List<ServerConfig>> entry : serverConfigsByServerId.entrySet()) {
                try {
                    ServerTrafficCollectSummary summary = collectServerTrafficForServer(entry.getKey(), entry.getValue(), now);
                    processedServerCount++;
                    TrafficCollectResult result = summary.result();
                    successCount += result.successCount();
                    skippedCount += result.skippedCount();
                    failureCount += result.failureCount();
                    log.info("服务器流量统计处理完成，serverId={}, serverName={}, 配置数: {}, 成功: {}, 跳过: {}, 失败: {}, 耗时: {} ms",
                            summary.serverId(), summary.serverName(), summary.configCount(),
                            result.successCount(), result.skippedCount(), result.failureCount(), summary.elapsedMillis());
                } catch (Exception e) {
                    log.error("按服务器处理流量统计时发生错误, serverId={}", entry.getKey(), e);
                    failureCount++;
                }
            }

            log.info("流量统计收集完成 - 服务器数: {}, 配置数: {}, 成功: {}, 跳过: {}, 失败: {}, 耗时: {} ms",
                    processedServerCount, serverConfigs.size(), successCount, skippedCount, failureCount,
                    elapsedMillis(startedAt));

            if (failureCount > 0) {
                sendWarningNotification("AirOpsCat 流量统计",
                        String.format("流量统计收集完成，成功: %d, 跳过: %d, 失败: %d",
                                successCount, skippedCount, failureCount));
            }
        } catch (Exception e) {
            log.error("执行流量统计任务时发生错误", e);
            sendErrorNotification("AirOpsCat 流量统计失败", "执行流量统计任务时发生错误: " + e.getMessage());
        } finally {
            log.info("流量统计任务执行完成，耗时: {} ms", elapsedMillis(startedAt));
        }
    }

    List<ServerConfig> loadServerConfigs() {
        return serverConfigRepository.findAll().list();
    }

    Server loadServer(Long serverId) {
        return serverRepository.findById(serverId);
    }

    boolean isSupportedConfigType(String configType) {
        return getRegisteredCollectorTypes().contains(configType);
    }

    Set<String> getRegisteredCollectorTypes() {
        return Set.of("sing-box", "singbox");
    }

    SshConnection createConnection(Server server) {
        return sshConnectionService.createConnection(scheduledSupport.buildSshConfig(server));
    }

    void sendWarningNotification(String title, String body) {
        barkService.sendWarningNotification(title, body);
    }

    void sendErrorNotification(String title, String body) {
        barkService.sendErrorNotification(title, body);
    }

    private ServerTrafficCollectSummary collectServerTrafficForServer(Long serverId, List<ServerConfig> serverConfigs, LocalDateTime now) {
        long startedAt = System.nanoTime();
        int skippedCount = 0;
        List<ServerConfig> eligibleConfigs = new ArrayList<>();

        for (ServerConfig serverConfig : serverConfigs) {
            String configType = normalizeConfigType(serverConfig.getConfigType());
            if (configType == null) {
                log.debug("配置 {} 未设置 configType，跳过流量统计", serverConfig.getId());
                skippedCount++;
                continue;
            }
            if (serverConfig.getEnabled() != null && serverConfig.getEnabled() == 0) {
                log.debug("配置 {} 已禁用，跳过流量统计", serverConfig.getId());
                skippedCount++;
                continue;
            }
            if (!isSupportedConfigType(configType)) {
                log.debug("配置 {} 的内核 {} 暂无流量采集器，跳过", serverConfig.getId(), configType);
                skippedCount++;
                continue;
            }
            eligibleConfigs.add(serverConfig);
        }

        Server server = loadServer(serverId);
        if (scheduledSupport.isInvalidServer(server, now.toLocalDate())) {
            logInvalidServer(serverId, server, now);
            return new ServerTrafficCollectSummary(serverId, server == null ? null : server.getName(),
                    serverConfigs.size(), new TrafficCollectResult(0, skippedCount + eligibleConfigs.size(), 0),
                    elapsedMillis(startedAt));
        }

        if (eligibleConfigs.isEmpty()) {
            return new ServerTrafficCollectSummary(serverId, server.getName(), serverConfigs.size(),
                    new TrafficCollectResult(0, skippedCount, 0), elapsedMillis(startedAt));
        }

        try (SshConnection connection = createConnection(server)) {
            TrafficCollectResult result = collectServerTrafficStats(connection, server, eligibleConfigs);
            return new ServerTrafficCollectSummary(serverId, server.getName(), serverConfigs.size(),
                    result.merge(new TrafficCollectResult(0, skippedCount, 0)), elapsedMillis(startedAt));
        } catch (Exception e) {
            log.error("创建 SSH 连接或收集服务器流量统计失败, serverId={}, serverName={}",
                    server.getId(), server.getName(), e);
            return new ServerTrafficCollectSummary(serverId, server.getName(), serverConfigs.size(),
                    new TrafficCollectResult(0, skippedCount, eligibleConfigs.size()), elapsedMillis(startedAt));
        }
    }

    TrafficCollectResult collectServerTrafficStats(SshConnection connection, Server server, List<ServerConfig> serverConfigs) {
        int successCount = 0;
        int skippedCount = 0;
        int failureCount = 0;

        for (ServerConfig serverConfig : serverConfigs) {
            String configType = normalizeConfigType(serverConfig.getConfigType());
            Map<String, UserTrafficStats> allTrafficStats =
                    singBoxTrafficStatsCollector.collectUserTrafficStats(connection, server, serverConfig);
            if (allTrafficStats.isEmpty()) {
                log.debug("服务器 {} 的 {} 内核没有流量统计数据", server.getId(), configType);
                skippedCount++;
                continue;
            }

            BigDecimal multiple = server.getMultiple() == null ? BigDecimal.ONE : server.getMultiple();
            long totalUploadBytes = 0L;
            long totalDownloadBytes = 0L;

            List<Account> accountList = accountRepository.findByAccountNos(allTrafficStats.keySet());
            Map<String, Account> accountMap = accountList.stream()
                    .collect(Collectors.toMap(Account::getAccountNo, account -> account));

            for (Map.Entry<String, UserTrafficStats> entry : allTrafficStats.entrySet()) {
                String accountNo = entry.getKey();
                UserTrafficStats trafficStats = entry.getValue();
                if (trafficStats == null) {
                    log.debug("服务器 {} 上的用户 {} 没有流量数据", server.getName(), accountNo);
                    skippedCount++;
                    continue;
                }

                try {
                    long adjustedUpload = multiple.multiply(new BigDecimal(trafficStats.uploadBytes())).longValue();
                    long adjustedDownload = trafficStats.downloadBytes();
                    totalUploadBytes += adjustedUpload;
                    totalDownloadBytes += adjustedDownload;

                    Account account = accountMap.get(accountNo);
                    if (account == null) {
                        log.warn("服务器 {} 上未找到用户 {}", server.getName(), accountNo);
                        skippedCount++;
                        continue;
                    }

                    AccountTrafficStats stats = accountTrafficStatsService.saveOrUpdateTrafficStats(
                            account.getId(),
                            account.getUserId(),
                            account.getPeriodType(),
                            account.getToDate(),
                            adjustedUpload,
                            adjustedDownload
                    );
                    accountTrafficOverQuotaService.handle(account, stats);
                    successCount++;

                    log.debug("处理服务器 {} 上的用户 {} 流量统计: 上传 {} 字节, 下载 {} 字节, core={}",
                            server.getName(), accountNo, trafficStats.uploadBytes(), trafficStats.downloadBytes(),
                            configType);
                } catch (Exception e) {
                    log.error("处理用户 {} 流量统计失败: {}", accountNo, e.getMessage(), e);
                    failureCount++;
                }
            }

            if (totalUploadBytes > 0 || totalDownloadBytes > 0) {
                serverTrafficStatsService.saveOrUpdateTrafficStats(
                        server.getId(),
                        server.getBandwidthDate(),
                        totalUploadBytes,
                        totalDownloadBytes
                );
            }
        }

        return new TrafficCollectResult(successCount, skippedCount, failureCount);
    }

    private void logInvalidServer(Long serverId, Server server, LocalDateTime now) {
        if (server == null) {
            log.debug("服务器 ID:{} 不存在，跳过流量统计", serverId);
        } else if (server.getDisabled() != null && server.getDisabled() == 1) {
            log.debug("服务器 [{}, ID:{}] 未启用，跳过流量统计", server.getName(), server.getId());
        } else if (server.getExpireDate() != null && now.toLocalDate().isAfter(server.getExpireDate())) {
            log.debug("服务器 [{}, ID:{}] 已过期，跳过流量统计", server.getName(), server.getId());
        } else if (server.getExternal() != null && server.getExternal() == 1) {
            log.debug("服务器 [{}, ID:{}] 为托管，跳过流量统计", server.getName(), server.getId());
        }
    }

    private String normalizeConfigType(String configType) {
        if (configType == null || configType.isBlank()) {
            return null;
        }
        return configType.trim().toLowerCase();
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    record TrafficCollectResult(int successCount, int skippedCount, int failureCount) {
        TrafficCollectResult merge(TrafficCollectResult other) {
            return new TrafficCollectResult(
                    successCount + other.successCount,
                    skippedCount + other.skippedCount,
                    failureCount + other.failureCount
            );
        }
    }

    private record ServerTrafficCollectSummary(Long serverId, String serverName, int configCount,
                                               TrafficCollectResult result, long elapsedMillis) {
    }
}
