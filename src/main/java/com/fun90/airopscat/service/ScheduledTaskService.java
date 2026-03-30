package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.service.deployment.NodeDeploymentService;
import com.fun90.airopscat.service.expiration.MonitorNotificationService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.service.traffic.TrafficStatsCollector;
import com.fun90.airopscat.service.traffic.UserTrafficStats;
import com.fun90.airopscat.service.traffic.registry.TrafficStatsCollectorRegistry;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 定时任务服务
 * 负责执行各种定时任务，如检查过期账户、重新部署节点等
 */
@Slf4j
@ApplicationScoped
public class ScheduledTaskService {

    @Inject
    AccountRepository accountRepository;

    @Inject
    TagRepository tagRepository;

    @Inject
    NodeDeploymentService nodeDeploymentService;

    @Inject
    BarkService barkService;

    @Inject
    ServerConfigRepository serverConfigRepository;

    @Inject
    ServerRepository serverRepository;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    AccountTrafficStatsService accountTrafficStatsService;

    @Inject
    ServerTrafficStatsService serverTrafficStatsService;

    @Inject
    ServerMonitorStatsService serverMonitorStatsService;

    @Inject
    MonitorNotificationService monitorNotificationService;

    @Inject
    TrafficStatsCollectorRegistry trafficStatsCollectorRegistry;

    @ConfigProperty(name = "airopscat.server.monitor.enabled", defaultValue = "true")
    boolean serverMonitorEnabled;

    /**
     * 每天凌晨5点执行的任务
     * 检查未禁用但已过期的账户，并重新部署相关的节点
     */
    @Scheduled(cron = "{airopscat.server.monitor.cron:0 0 5 * * ?}", timeZone = "Asia/Shanghai")
    @Transactional
    public void checkExpiredAccountsAndRedeployNodes() {
        log.info("开始执行定时任务：检查过期账户并重新部署节点");

        try {
            // 1. 查询未禁用但已过期的账户
            List<Account> expiredAccounts = accountRepository.findExpiredButNotDisabledAccounts(LocalDateTime.now());

            if (expiredAccounts.isEmpty()) {
                log.info("没有找到未禁用但已过期的账户，任务结束");
                return;
            }

            log.info("找到 {} 个未禁用但已过期的账户", expiredAccounts.size());

            // 2. 获取这些账户关联的所有节点ID
            List<Node> nodes = tagRepository.findNodesByAccountIds(expiredAccounts.stream().map(Account::getId).distinct().toList());

            if (nodes.isEmpty()) {
                log.info("过期账户没有关联的节点，任务结束");
                return;
            }

            log.info("需要重新部署的节点数量: {}", nodes.size());

            // 3. 批量重新部署节点
            List<DeploymentResult> deploymentResults = nodeDeploymentService.deployNodesForcibly(
                    nodes.stream().toList()
            );

            // 4. 批量禁用过期账户 (使用编程式事务)
            try {
                accountRepository.disableExpiredAccounts(
                        expiredAccounts.stream().map(Account::getId).collect(Collectors.toList()),
                        LocalDateTime.now()
                );
            } catch (Exception e) {
                log.error("批量禁用过期账户时发生错误: {}", e.getMessage());
            }

            // 5. 统计部署结果
            long successCount = deploymentResults.stream()
                    .mapToLong(result -> result.isSuccess() ? 1 : 0)
                    .sum();
            long failureCount = deploymentResults.size() - successCount;

            log.info("节点重新部署完成 - 成功: {}, 失败: {}", successCount, failureCount);

            // 6. 记录失败的部署结果
            if (failureCount > 0) {
                deploymentResults.stream()
                        .filter(result -> !result.isSuccess())
                        .forEach(result -> log.error("节点 {} 重新部署失败: {}",
                                result.getNodeId(), result.getMessage()));
            }
            barkService.sendInfoNotification("AirOpsCat 定时任务执行情况", "成功: " + successCount + " 失败: " + failureCount);

        } catch (Exception e) {
            log.error("执行定时任务时发生错误", e);
            barkService.sendErrorNotification("AirOpsCat 定时任务执行失败", "执行定时任务时发生错误: " + e.getMessage());
        }

        log.info("定时任务执行完成");
    }

    /**
     * 每隔15分钟执行的任务
     * 统计用户使用的流量，通过xray api命令获取数据并保存到AccountTrafficStats
     */
    @Scheduled(every = "15m")
    public void collectUserTrafficStats() {
        log.info("开始执行定时任务：收集用户流量统计");

        try {
            List<ServerConfig> serverConfigs = serverConfigRepository.findAll().list();

            if (serverConfigs.isEmpty()) {
                log.info("没有找到可用的内核配置，任务结束");
                return;
            }

            log.info("找到 {} 个内核配置，支持的流量采集器: {}", serverConfigs.size(),
                    trafficStatsCollectorRegistry.getRegisteredTypes());

            LocalDateTime now = LocalDateTime.now();
            int successCount = 0;
            int failureCount = 0;

            for (ServerConfig serverConfig : serverConfigs) {
                try {
                    String configType = normalizeConfigType(serverConfig.getConfigType());
                    if (configType == null) {
                        log.debug("配置 {} 未设置 configType，跳过流量统计", serverConfig.getId());
                        continue;
                    }
                    if (serverConfig.getEnabled() != null && serverConfig.getEnabled() == 0) {
                        log.debug("配置 {} 已禁用，跳过流量统计", serverConfig.getId());
                        continue;
                    }
                    if (!trafficStatsCollectorRegistry.getRegisteredTypes().contains(configType)) {
                        log.debug("配置 {} 的内核 {} 暂无流量采集器，跳过", serverConfig.getId(), configType);
                        continue;
                    }

                    // 获取服务器信息
                    Server server = serverRepository.findById(serverConfig.getServerId());
                    if (server == null) {
                        log.info("服务器[ID:{}] 不存在，跳过流量统计", serverConfig.getServerId());
                        continue;
                    }
                    if (server.getDisabled() == 1) {
                        log.info("服务器[{}, ID:{}] 未启用，跳过流量统计", server.getName(), server.getId());
                        continue;
                    }
                    if (server.getExpireDate() != null && now.toLocalDate().isAfter(server.getExpireDate())) {
                        log.info("服务器[{}, ID:{}] 已过期，跳过流量统计", server.getName(), server.getId());
                        continue;
                    }
                    if (server.getExternal() != null && server.getExternal() == 1) {
                        log.info("服务器[{}, ID:{}] 为托管，跳过流量统计", server.getName(), server.getId());
                        continue;
                    }

                    TrafficStatsCollector collector = trafficStatsCollectorRegistry.getStrategy(configType);
                    int serverSuccessCount = collectServerTrafficStats(server, serverConfig, collector);
                    successCount += serverSuccessCount;
                } catch (Exception e) {
                    log.error("处理服务器配置 {} 时发生错误: {}", serverConfig.getId(), e.getMessage(), e);
                    failureCount++;
                }
            }

            log.info("流量统计收集完成 - 成功: {} , 失败: {} ", successCount, failureCount);

            // 发送通知
            if (failureCount > 0) {
                barkService.sendWarningNotification("AirOpsCat 流量统计",
                    String.format("流量统计收集完成，成功: %d, 失败: %d", successCount, failureCount));
            }

        } catch (Exception e) {
            log.error("执行流量统计任务时发生错误", e);
            barkService.sendErrorNotification("AirOpsCat 流量统计失败", "执行流量统计任务时发生错误: " + e.getMessage());
        }

        log.info("流量统计任务执行完成");
    }

    /**
     * 检查当天到期的账号、服务器、域名，并发送提醒通知
     */
    @Scheduled(cron = "{airopscat.expiration.notify.cron:0 0 10 * * ?}", timeZone = "Asia/Shanghai")
    public void notifyExpiringResourcesToday() {
        monitorNotificationService.notify("account");
        monitorNotificationService.notify("server");
        monitorNotificationService.notify("domain");
    }

    /**
     * 服务器流量达到阈值提醒
     */
    @Scheduled(cron = "{airopscat.server.traffic.notify.cron:0 10 10 * * ?}", timeZone = "Asia/Shanghai")
    public void notifyServerTrafficThreshold() {
        monitorNotificationService.notify("server-traffic");
    }

    /**
     * 定时采集服务器监控指标
     */
    @Scheduled(
            cron = "{airopscat.server.monitor.cron:0 */5 * * * ?}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    public void collectServerMonitorStats() {
        if (!serverMonitorEnabled) {
            log.debug("服务器监控采集已禁用，跳过本次任务");
            return;
        }

        log.info("开始执行定时任务：采集服务器监控指标");

        try {
            List<Server> servers = serverRepository.findAll().list();
            if (servers.isEmpty()) {
                log.info("没有找到服务器，跳过监控采集");
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            List<Server> targetServers = new ArrayList<>();
            int skippedCount = 0;

            for (Server server : servers) {
                if (!shouldCollectServerMonitor(server, now)) {
                    skippedCount++;
                    continue;
                }
                targetServers.add(server);
            }

            if (targetServers.isEmpty()) {
                log.info("没有需要采集的服务器，跳过监控采集");
                return;
            }

            int successCount = 0;
            int failureCount = 0;

            List<CompletableFuture<MonitorCollectResult>> futures = targetServers.stream()
                    .map(server -> CompletableFuture.supplyAsync(
                            () -> collectServerMonitorSnapshot(server),
                            Infrastructure.getDefaultExecutor()))
                    .toList();

            for (CompletableFuture<MonitorCollectResult> future : futures) {
                try {
                    MonitorCollectResult result = future.join();
                    if (result == MonitorCollectResult.SUCCESS) {
                        successCount++;
                    } else if (result == MonitorCollectResult.SKIPPED) {
                        skippedCount++;
                    } else {
                        failureCount++;
                    }
                } catch (Exception e) {
                    failureCount++;
                    log.error("等待服务器监控采集结果时发生错误: {}", e.getMessage(), e);
                }
            }

            log.info("服务器监控采集完成 - 成功: {}, 跳过: {}, 失败: {}", successCount, skippedCount, failureCount);
        } catch (Exception e) {
            log.error("执行服务器监控采集任务时发生错误", e);
        }
    }

    private MonitorCollectResult collectServerMonitorSnapshot(Server server) {
        try {
            return serverMonitorStatsService.collectAndSave(server) != null
                    ? MonitorCollectResult.SUCCESS
                    : MonitorCollectResult.SKIPPED;
        } catch (Exception e) {
            log.error("采集服务器监控失败，serverId={}, ip={}, error={}",
                    server.getId(), server.getIp(), e.getMessage(), e);
            return MonitorCollectResult.FAILED;
        }
    }

    private enum MonitorCollectResult {
        SUCCESS,
        SKIPPED,
        FAILED
    }

    /**
     * 收集单个服务器的流量统计
     */
    private int collectServerTrafficStats(Server server, ServerConfig serverConfig, TrafficStatsCollector collector) {
        int successCount = 0;

        try {
            SshConfig sshConfig = createSshConfig(server);

            try (SshConnection connection = sshConnectionService.createConnection(sshConfig)) {
                Map<String, UserTrafficStats> allTrafficStats = collector.collectUserTrafficStats(connection, server, serverConfig);
                if (allTrafficStats.isEmpty()) {
                    log.info("服务器 {} 的 {} 内核没有流量统计数据", server.getId(), normalizeConfigType(serverConfig.getConfigType()));
                    return 0;
                }

                BigDecimal multiple = server.getMultiple() == null ? BigDecimal.ONE : server.getMultiple();
                long totalUploadBytes = 0L;
                long totalDownloadBytes = 0L;

                List<Account> accountList = accountRepository.findByAccountNos(allTrafficStats.keySet());
                Map<String, Account> accountMap = accountList.stream().collect(Collectors.toMap(Account::getAccountNo, account -> account));

                for (Map.Entry<String, UserTrafficStats> entry : allTrafficStats.entrySet()) {
                    String accountNo = entry.getKey();
                    UserTrafficStats trafficStats = entry.getValue();
                    if (trafficStats != null) {
                        try {
                            long adjustedUpload = multiple.multiply(new BigDecimal(trafficStats.uploadBytes())).longValue();
                            long adjustedDownload = multiple.multiply(new BigDecimal(trafficStats.downloadBytes())).longValue();
                            totalUploadBytes += adjustedUpload;
                            totalDownloadBytes += adjustedDownload;

                            Account account = accountMap.get(accountNo);
                            if (account != null) {
                                accountTrafficStatsService.saveOrUpdateTrafficStats(
                                    account.getId(),
                                    account.getUserId(),
                                    account.getPeriodType(),
                                    account.getToDate(),
                                    adjustedUpload,
                                    adjustedDownload
                                );
                                successCount++;

                                log.debug("处理服务器：{} 上的用户 {} 流量统计: 上传 {} 字节, 下载 {} 字节, core={}",
                                        server.getName(), accountNo, trafficStats.uploadBytes(), trafficStats.downloadBytes(),
                                        normalizeConfigType(serverConfig.getConfigType()));
                            } else {
                                log.warn("服务器：{} 上未找到用户 {}", server.getName(), accountNo);
                            }
                        } catch (Exception e) {
                            log.error("处理用户 {} 流量统计失败: {}", accountNo, e.getMessage());
                        }
                    } else {
                        log.debug("服务器：{} 上的用户 {} 没有流量数据", server.getName(), accountNo);
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

        } catch (Exception e) {
            log.error("收集服务器 {} 流量统计失败: {}", server.getId(), e.getMessage());
        }

        return successCount;
    }

    /**
     * 创建SSH配置
     */
    private SshConfig createSshConfig(Server server) {
        SshConfig sshConfig = new SshConfig();
        sshConfig.setHost(server.getIp());
        sshConfig.setPort(server.getSshPort() != null ? server.getSshPort() : 22);
        sshConfig.setUsername(server.getUsername());
        sshConfig.setTimeout(10000);
        // 现在 auth 字段通过 JPA 转换器自动解密，直接使用即可
        String auth = server.getAuth();

        if ("PASSWORD".equalsIgnoreCase(server.getAuthType()) || "password".equalsIgnoreCase(server.getAuthType())) {
            sshConfig.setPassword(auth);
        } else {
            sshConfig.setPrivateKeyContent(auth);
        }

        return sshConfig;
    }

    private boolean shouldCollectServerMonitor(Server server, LocalDateTime now) {
        if (server == null) {
            return false;
        }
        if (server.getDisabled() != null && server.getDisabled() == 1) {
            return false;
        }
        if (server.getExternal() != null && server.getExternal() == 1) {
            return false;
        }
        return server.getExpireDate() == null || !now.toLocalDate().isAfter(server.getExpireDate());
    }

    /**
     * 每天早上8点执行的任务
     * 清理Xray配置路径下5天前的备份文件（config.json.backup.{时间戳}）
     */
    @Scheduled(cron = "0 0 8 * * ?", timeZone = "Asia/Shanghai")
    public void cleanupOldXrayBackupFiles() {
        log.info("开始执行定时任务：清理Xray旧备份文件");

        try {
            // 1. 获取所有Xray类型的服务器配置
            List<ServerConfig> xrayConfigs = serverConfigRepository.findByConfigType("xray");

            if (xrayConfigs.isEmpty()) {
                log.info("没有找到Xray配置，任务结束");
                return;
            }

            log.info("找到 {} 个Xray配置", xrayConfigs.size());

            int totalCleaned = 0;
            int successCount = 0;
            int failureCount = 0;

            // 2. 遍历每个Xray配置
            for (ServerConfig serverConfig : xrayConfigs) {
                try {
                    // 获取服务器信息
                    Server server = serverRepository.findById(serverConfig.getServerId());
                    if (server == null) {
                        log.warn("服务器 {} 不存在，跳过", serverConfig.getServerId());
                        continue;
                    }
                    if (server.getDisabled() == 1) {
                        log.info("服务器 {} 失效，清除数据", serverConfig.getServerId());
                        serverConfigRepository.deleteById(serverConfig.getId());
                        continue;
                    }

                    // 获取配置文件路径的目录
                    String configPath = serverConfig.getPath();
                    if (configPath == null || configPath.trim().isEmpty()) {
                        log.warn("服务器 {} 的Xray配置路径为空，跳过", server.getId());
                        continue;
                    }

                    // 提取目录路径
                    String configDir = configPath.substring(0, configPath.lastIndexOf('/'));

                    // 清理该服务器上的旧备份文件
                    int cleanedCount = cleanupServerBackupFiles(server, configDir);
                    totalCleaned += cleanedCount;
                    successCount++;

                    log.info("服务器 {} 清理了 {} 个旧备份文件", server.getName(), cleanedCount);

                } catch (Exception e) {
                    log.error("处理服务器配置 {} 时发生错误: {}", serverConfig.getId(), e.getMessage());
                    failureCount++;
                }
            }

            log.info("备份文件清理完成 - 总共清理: {} 个文件, 成功处理: {} 个配置, 失败: {} 个配置",
                     totalCleaned, successCount, failureCount);

            // 发送通知
            if (totalCleaned > 0 || failureCount > 0) {
                String message = String.format("清理了 %d 个旧备份文件，成功处理: %d 个配置, 失败: %d 个配置",
                                              totalCleaned, successCount, failureCount);
                if (failureCount > 0) {
                    barkService.sendWarningNotification("AirOpsCat 备份清理", message);
                } else {
                    barkService.sendInfoNotification("AirOpsCat 备份清理", message);
                }
            }

        } catch (Exception e) {
            log.error("执行备份文件清理任务时发生错误", e);
            barkService.sendErrorNotification("AirOpsCat 备份清理失败", "执行备份文件清理任务时发生错误: " + e.getMessage());
        }

        log.info("备份文件清理任务执行完成");
    }

    /**
     * 清理单个服务器的旧备份文件
     */
    private int cleanupServerBackupFiles(Server server, String configDir) {
        int cleanedCount = 0;

        try {
            // 创建SSH连接
            SshConfig sshConfig = createSshConfig(server);

            try (SshConnection connection = sshConnectionService.createConnection(sshConfig)) {

                // 构建清理命令：查找5天前的备份文件并删除
                // 查找 config.json.backup.* 格式的文件，修改时间超过5天的
                String findCommand = String.format(
                    "find %s -name 'config.json.backup.*' -type f -mtime +5 2>/dev/null || true",
                    configDir
                );

                CommandResult findResult = connection.executeCommand(findCommand);

                if (!findResult.isSuccess()) {
                    log.warn("查找服务器 {} 备份文件失败: {}", server.getId(), findResult.getStderr());
                    return 0;
                }

                String output = findResult.getStdout();
                if (output == null || output.trim().isEmpty()) {
                    log.debug("服务器 {} 没有找到需要清理的旧备份文件", server.getId());
                    return 0;
                }

                // 分析找到的文件
                String[] backupFiles = output.trim().split("\n");
                log.info("服务器 {} 找到 {} 个旧备份文件需要清理", server.getName(), backupFiles.length);

                // 删除这些文件
                for (String backupFile : backupFiles) {
                    if (backupFile.trim().isEmpty()) {
                        continue;
                    }

                    String deleteCommand = String.format("rm -f '%s'", backupFile.trim());
                    CommandResult deleteResult = connection.executeCommand(deleteCommand);

                    if (deleteResult.isSuccess()) {
                        cleanedCount++;
                        log.debug("删除旧备份文件: {}", backupFile.trim());
                    } else {
                        log.warn("删除备份文件 {} 失败: {}", backupFile.trim(), deleteResult.getStderr());
                    }
                }

            }

        } catch (Exception e) {
            log.error("清理服务器 {} 备份文件失败: {}", server.getId(), e.getMessage());
        }

        return cleanedCount;
    }

    private String normalizeConfigType(String configType) {
        if (configType == null || configType.isBlank()) {
            return null;
        }
        return configType.trim().toLowerCase();
    }
}
