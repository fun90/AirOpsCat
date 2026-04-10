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
import com.fun90.airopscat.service.traffic.UserTrafficStats;
import com.fun90.airopscat.service.traffic.impl.SingBoxClashApiTrafficStatsCollector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class TrafficStatsTask {

    private static final String CORE_TYPE_SING_BOX = "sing-box";
    private static final String CORE_TYPE_SINGBOX = "singbox";

    @Inject
    ServerConfigRepository serverConfigRepository;

    @Inject
    ServerRepository serverRepository;

    @Inject
    AccountRepository accountRepository;

    @Inject
    AccountTrafficStatsService accountTrafficStatsService;

    @Inject
    ServerTrafficStatsService serverTrafficStatsService;

    @Inject
    ScheduledSupport scheduledSupport;

    @Inject
    BarkService barkService;

    @Inject
    SingBoxClashApiTrafficStatsCollector singBoxCollector;

    public void collectForAllServers() {
        long startedAt = System.nanoTime();
        log.info("开始执行 sing-box 流量统计采集");

        try {
            List<ServerConfig> serverConfigs = loadEnabledSingBoxConfigs();
            if (serverConfigs.isEmpty()) {
                log.info("未找到启用的 sing-box 配置，跳过流量统计，耗时: {} ms", elapsedMillis(startedAt));
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            Map<Long, List<ServerConfig>> serverConfigsByServerId = new LinkedHashMap<>();
            for (ServerConfig serverConfig : serverConfigs) {
                if (serverConfig.getServerId() == null) {
                    continue;
                }
                serverConfigsByServerId.computeIfAbsent(serverConfig.getServerId(), ignored -> new ArrayList<>())
                        .add(serverConfig);
            }

            int successCount = 0;
            int skippedCount = 0;
            int failureCount = 0;
            int processedServerCount = 0;

            for (Map.Entry<Long, List<ServerConfig>> entry : serverConfigsByServerId.entrySet()) {
                try {
                    Server server = loadServer(entry.getKey());
                    if (scheduledSupport.isInvalidServer(server, now.toLocalDate())) {
                        logInvalidServer(entry.getKey(), server, now);
                        skippedCount += entry.getValue().size();
                        continue;
                    }

                    TrafficCollectResult result = collectServerTrafficStats(server, entry.getValue());
                    processedServerCount++;
                    successCount += result.successCount();
                    skippedCount += result.skippedCount();
                    failureCount += result.failureCount();
                } catch (Exception e) {
                    log.error("采集服务器流量统计失败, serverId={}", entry.getKey(), e);
                    failureCount += entry.getValue().size();
                }
            }

            log.info("sing-box 流量统计收集完成 - 服务器数: {}, 配置数: {}, 成功: {}, 跳过: {}, 失败: {}, 耗时: {} ms",
                    processedServerCount, serverConfigs.size(), successCount, skippedCount, failureCount,
                    elapsedMillis(startedAt));

            if (failureCount > 0) {
                barkService.sendWarningNotification("AirOpsCat 流量统计",
                        String.format("流量统计收集完成，成功: %d, 跳过: %d, 失败: %d",
                                successCount, skippedCount, failureCount));
            }
        } catch (Exception e) {
            log.error("执行 sing-box 流量统计任务时发生错误", e);
            barkService.sendErrorNotification("AirOpsCat 流量统计失败", "执行流量统计任务时发生错误: " + e.getMessage());
        } finally {
            log.info("sing-box 流量统计任务执行完成，耗时: {} ms", elapsedMillis(startedAt));
        }
    }

    List<ServerConfig> loadEnabledSingBoxConfigs() {
        return serverConfigRepository.findAll().list().stream()
                .filter(this::isEnabledSingBoxConfig)
                .toList();
    }

    Server loadServer(Long serverId) {
        return serverRepository.findById(serverId);
    }

    private boolean isEnabledSingBoxConfig(ServerConfig serverConfig) {
        if (serverConfig == null || serverConfig.getServerId() == null) {
            return false;
        }
        if (serverConfig.getEnabled() != null && serverConfig.getEnabled() == 0) {
            return false;
        }
        String configType = normalizeConfigType(serverConfig.getConfigType());
        return CORE_TYPE_SING_BOX.equals(configType) || CORE_TYPE_SINGBOX.equals(configType);
    }

    TrafficCollectResult collectServerTrafficStats(Server server, List<ServerConfig> serverConfigs) {
        int successCount = 0;
        int skippedCount = 0;
        int failureCount = 0;

        for (ServerConfig serverConfig : serverConfigs) {
            Map<String, UserTrafficStats> allTrafficStats = singBoxCollector.collectUserTrafficStats(null, server, serverConfig);
            if (allTrafficStats.isEmpty()) {
                log.debug("服务器 {} 的 sing-box 配置没有流量统计数据", server.getId());
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
    }
}
