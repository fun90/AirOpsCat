package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.AccountTrafficStatsService;
import com.fun90.airopscat.service.BarkService;
import com.fun90.airopscat.service.ServerTrafficStatsService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.service.traffic.TrafficStatsCollector;
import com.fun90.airopscat.service.traffic.UserTrafficStats;
import com.fun90.airopscat.service.traffic.registry.TrafficStatsCollectorRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    ServerTrafficStatsService serverTrafficStatsService;

    @Inject
    TrafficStatsCollectorRegistry trafficStatsCollectorRegistry;

    @Inject
    ScheduledSupport scheduledSupport;

    @Inject
    BarkService barkService;

    public void collectUserTrafficStats() {
        long startedAt = System.nanoTime();
        log.info("开始执行定时任务：收集用户流量统计");

        try {
            List<ServerConfig> serverConfigs = serverConfigRepository.findAll().list();
            if (serverConfigs.isEmpty()) {
                log.info("用户流量统计任务结束，没有找到可用的内核配置，耗时: {} ms",
                        elapsedMillis(startedAt));
                return;
            }

            log.info("找到 {} 个内核配置，支持的流量采集器: {}", serverConfigs.size(),
                    trafficStatsCollectorRegistry.getRegisteredTypes());

            LocalDateTime now = LocalDateTime.now();
            int successCount = 0;
            int skippedCount = 0;
            int failureCount = 0;

            for (ServerConfig serverConfig : serverConfigs) {
                try {
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
                    if (!trafficStatsCollectorRegistry.getRegisteredTypes().contains(configType)) {
                        log.debug("配置 {} 的内核 {} 暂无流量采集器，跳过", serverConfig.getId(), configType);
                        skippedCount++;
                        continue;
                    }

                    Server server = serverRepository.findById(serverConfig.getServerId());
                    if (scheduledSupport.isInvalidServer(server, now.toLocalDate())) {
                        if (server == null) {
                            log.debug("服务器 ID:{} 不存在，跳过流量统计", serverConfig.getServerId());
                        } else if (server.getDisabled() != null && server.getDisabled() == 1) {
                            log.debug("服务器 [{}, ID:{}] 未启用，跳过流量统计", server.getName(), server.getId());
                        } else if (server.getExpireDate() != null && now.toLocalDate().isAfter(server.getExpireDate())) {
                            log.debug("服务器 [{}, ID:{}] 已过期，跳过流量统计", server.getName(), server.getId());
                        } else if (server.getExternal() != null && server.getExternal() == 1) {
                            log.debug("服务器 [{}, ID:{}] 为托管，跳过流量统计", server.getName(), server.getId());
                        }
                        skippedCount++;
                        continue;
                    }

                    TrafficStatsCollector collector = trafficStatsCollectorRegistry.getStrategy(configType);
                    TrafficCollectResult result = collectServerTrafficStats(server, serverConfig, collector);
                    successCount += result.successCount();
                    skippedCount += result.skippedCount();
                    failureCount += result.failureCount();
                } catch (Exception e) {
                    log.error("处理服务器配置 {} 时发生错误: {}", serverConfig.getId(), e.getMessage(), e);
                    failureCount++;
                }
            }

            log.info("流量统计收集完成 - 配置数: {}, 成功: {}, 跳过: {}, 失败: {}, 耗时: {} ms",
                    serverConfigs.size(), successCount, skippedCount, failureCount, elapsedMillis(startedAt));

            if (failureCount > 0) {
                barkService.sendWarningNotification("AirOpsCat 流量统计",
                        String.format("流量统计收集完成，成功: %d, 跳过: %d, 失败: %d",
                                successCount, skippedCount, failureCount));
            }
        } catch (Exception e) {
            log.error("执行流量统计任务时发生错误", e);
            barkService.sendErrorNotification("AirOpsCat 流量统计失败", "执行流量统计任务时发生错误: " + e.getMessage());
        } finally {
            log.info("流量统计任务执行完成，耗时: {} ms", elapsedMillis(startedAt));
        }
    }

    private TrafficCollectResult collectServerTrafficStats(Server server, ServerConfig serverConfig, TrafficStatsCollector collector) {
        int successCount = 0;
        int skippedCount = 0;
        int failureCount = 0;

        try (SshConnection connection = sshConnectionService.createConnection(
                scheduledSupport.buildSshConfig(server))) {
            Map<String, UserTrafficStats> allTrafficStats = collector.collectUserTrafficStats(connection, server, serverConfig);
            if (allTrafficStats.isEmpty()) {
                log.debug("服务器 {} 的 {} 内核没有流量统计数据", server.getId(), normalizeConfigType(serverConfig.getConfigType()));
                return new TrafficCollectResult(0, 1, 0);
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
                    long adjustedDownload = multiple.multiply(new BigDecimal(trafficStats.downloadBytes())).longValue();
                    totalUploadBytes += adjustedUpload;
                    totalDownloadBytes += adjustedDownload;

                    Account account = accountMap.get(accountNo);
                    if (account == null) {
                        log.warn("服务器 {} 上未找到用户 {}", server.getName(), accountNo);
                        skippedCount++;
                        continue;
                    }

                    accountTrafficStatsService.saveOrUpdateTrafficStats(
                            account.getId(),
                            account.getUserId(),
                            account.getPeriodType(),
                            account.getToDate(),
                            adjustedUpload,
                            adjustedDownload
                    );
                    successCount++;

                    log.debug("处理服务器 {} 上的用户 {} 流量统计: 上传 {} 字节, 下载 {} 字节, core={}",
                            server.getName(), accountNo, trafficStats.uploadBytes(), trafficStats.downloadBytes(),
                            normalizeConfigType(serverConfig.getConfigType()));
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
        } catch (Exception e) {
            log.error("收集服务器 {} 流量统计失败: {}", server.getId(), e.getMessage(), e);
            failureCount++;
        }

        return new TrafficCollectResult(successCount, skippedCount, failureCount);
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

    private record TrafficCollectResult(int successCount, int skippedCount, int failureCount) {
    }
}
