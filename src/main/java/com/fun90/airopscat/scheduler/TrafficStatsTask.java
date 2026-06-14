package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.AccountRepository;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class TrafficStatsTask {

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
            List<Server> servers = loadRuntimeTargetServers();
            if (servers.isEmpty()) {
                log.info("用户流量统计任务结束，没有找到运行中的目标服务器，耗时: {} ms",
                        elapsedMillis(startedAt));
                return;
            }

            log.info("找到 {} 台运行中的目标服务器", servers.size());

            int successCount = 0;
            int skippedCount = 0;
            int failureCount = 0;

            for (Server server : servers) {
                try {
                    TrafficCollectResult result = collectServerTrafficForServer(server);
                    successCount += result.successCount();
                    skippedCount += result.skippedCount();
                    failureCount += result.failureCount();
                } catch (Exception e) {
                    log.error("按服务器处理流量统计时发生错误, serverId={}", server.getId(), e);
                    failureCount++;
                }
            }

            log.info("流量统计收集完成 - 服务器数: {}, 成功: {}, 跳过: {}, 失败: {}, 耗时: {} ms",
                    servers.size(), successCount, skippedCount, failureCount,
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

    List<Server> loadRuntimeTargetServers() {
        return serverRepository.findRuntimeTargetServers(LocalDateTime.now().toLocalDate());
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

    private TrafficCollectResult collectServerTrafficForServer(Server server) {
        long startedAt = System.nanoTime();
        try (SshConnection connection = createConnection(server)) {
            TrafficCollectResult result = collectServerTrafficStats(connection, server);
            log.info("服务器流量统计处理完成，serverId={}, serverName={}, 成功: {}, 跳过: {}, 失败: {}, 耗时: {} ms",
                    server.getId(), server.getName(), result.successCount(), result.skippedCount(),
                    result.failureCount(), elapsedMillis(startedAt));
            return result;
        } catch (Exception e) {
            log.error("创建 SSH 连接或收集服务器流量统计失败, serverId={}, serverName={}",
                    server.getId(), server.getName(), e);
            return new TrafficCollectResult(0, 0, 1);
        }
    }

    TrafficCollectResult collectServerTrafficStats(SshConnection connection, Server server) {
        int successCount = 0;
        int skippedCount = 0;
        int failureCount = 0;

        Map<String, UserTrafficStats> allTrafficStats =
                singBoxTrafficStatsCollector.collectUserTrafficStats(connection, server);
        if (allTrafficStats.isEmpty()) {
            log.debug("服务器 {} 没有流量统计数据", server.getId());
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

                log.debug("处理服务器 {} 上的用户 {} 流量统计: 上传 {} 字节, 下载 {} 字节",
                        server.getName(), accountNo, trafficStats.uploadBytes(), trafficStats.downloadBytes());
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

        return new TrafficCollectResult(successCount, skippedCount, failureCount);
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

}
