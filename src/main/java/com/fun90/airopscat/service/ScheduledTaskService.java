package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.XrayConfig;
import com.fun90.airopscat.model.dto.xray.setting.inbound.ShadowsocksInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.SocksInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.VlessInboundSetting;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.service.deployment.NodeDeploymentService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.service.expiration.MonitorNotificationService;
import com.fun90.airopscat.util.JsonUtil;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
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
    MonitorNotificationService monitorNotificationService;

    /**
     * 每天凌晨5点执行的任务
     * 检查未禁用但已过期的账户，并重新部署相关的节点
     */
    @Scheduled(cron = "0 0 5 * * ?", timeZone = "Asia/Shanghai")
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
            // 1. 获取所有Xray类型的服务器配置
            List<ServerConfig> xrayConfigs = serverConfigRepository.findByConfigType("xray");
            
            if (xrayConfigs.isEmpty()) {
                log.info("没有找到Xray配置，任务结束");
                return;
            }
            
            log.info("找到 {} 个Xray配置", xrayConfigs.size());
            
            // 2. 当前时间（用于记录统计时间）
            LocalDateTime now = LocalDateTime.now();
            
            int successCount = 0;
            int failureCount = 0;
            
            // 3. 遍历每个Xray配置
            for (ServerConfig serverConfig : xrayConfigs) {
                try {
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

                    // 收集该服务器上所有用户的流量统计
                    int serverSuccessCount = collectServerTrafficStats(server);
                    successCount += serverSuccessCount;
                    
                } catch (Exception e) {
                    log.error("处理服务器配置 {} 时发生错误: {}", serverConfig.getId(), e.getMessage());
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
     * 收集单个服务器的流量统计
     */
    private int collectServerTrafficStats(Server server) {
        int successCount = 0;
        
        try {
            // 创建SSH连接
            SshConfig sshConfig = createSshConfig(server);
            
            try (SshConnection connection = sshConnectionService.createConnection(sshConfig)) {
                
                // 使用statsquery一次性获取所有流量统计数据
                Map<String, TrafficStats> allTrafficStats = getAllXrayTrafficStats(connection);
                
                if (allTrafficStats.isEmpty()) {
                    log.info("服务器 {} 没有流量统计数据", server.getId());
                    return 0;
                }

                // 流量倍率
                BigDecimal multiple = server.getMultiple() == null ? BigDecimal.ONE : server.getMultiple();
                
                long totalUploadBytes = 0L;
                long totalDownloadBytes = 0L;

                List<Account> accountList = accountRepository.findByAccountNos(allTrafficStats.keySet());
                Map<String, Account> accountMap = accountList.stream().collect(Collectors.toMap(Account::getAccountNo, account -> account));

                // 处理每个用户的流量统计
                for (Map.Entry<String, TrafficStats> entry : allTrafficStats.entrySet()) {
                    String accountNo = entry.getKey();
                    TrafficStats trafficStats = entry.getValue();
                    if (trafficStats != null) {
                        try {
                            long adjustedUpload = multiple.multiply(new BigDecimal(trafficStats.uploadBytes())).longValue();
                            long adjustedDownload = multiple.multiply(new BigDecimal(trafficStats.downloadBytes())).longValue();
                            totalUploadBytes += adjustedUpload;
                            totalDownloadBytes += adjustedDownload;

                            // 查找对应的账户
                            Account account = accountMap.get(accountNo);
                            if (account != null) {
                                // 智能保存或更新流量统计（根据当前时间查询已有记录，匹配则累加，否则新增）
                                accountTrafficStatsService.saveOrUpdateTrafficStats(
                                    account.getId(),
                                    account.getUserId(),
                                    account.getPeriodType(),
                                    account.getToDate(),
                                    adjustedUpload,
                                    adjustedDownload
                                );
                                successCount++;
                                
                                log.debug("处理服务器：{} 上的用户 {} 流量统计: 上传 {} 字节, 下载 {} 字节",
                                        server.getName(), accountNo, trafficStats.uploadBytes(), trafficStats.downloadBytes());
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
     * 从入站配置中提取用户邮箱列表
     */
    private List<String> extractUserEmailsFromInbound(InboundConfig inbound) {
        List<String> userEmails = new ArrayList<>();
        
        if (inbound.getSettings() instanceof VlessInboundSetting vlessSettings) {
            if (vlessSettings.getClients() != null) {
                for (VlessInboundSetting.VlessClient client : vlessSettings.getClients()) {
                    if (client.getEmail() != null && !client.getEmail().trim().isEmpty()) {
                        userEmails.add(client.getEmail());
                    }
                }
            }
        } else if (inbound.getSettings() instanceof ShadowsocksInboundSetting ssSettings) {
            if (ssSettings.getClients() != null) {
                for (ShadowsocksInboundSetting.ShadowsocksClient client : ssSettings.getClients()) {
                    if (client.getEmail() != null && !client.getEmail().trim().isEmpty()) {
                        userEmails.add(client.getEmail());
                    }
                }
            }
        } else if (inbound.getSettings() instanceof SocksInboundSetting socksSettings) {
            if (socksSettings.getAccounts() != null) {
                for (SocksInboundSetting.SocksAccount account : socksSettings.getAccounts()) {
                    if (account.getUser() != null && !account.getUser().trim().isEmpty()) {
                        userEmails.add(account.getUser());
                    }
                }
            }
        }
        
        return userEmails;
    }
    
    /**
     * 通过xray api statsquery一次性获取所有流量统计数据
     */
    private Map<String, TrafficStats> getAllXrayTrafficStats(SshConnection connection) {
        Map<String, TrafficStats> trafficStatsMap = new HashMap<>();
        
        try {
            // 使用statsquery命令一次性获取所有统计数据，并重置计数器
            String command = "xray api statsquery --server=127.0.0.1:100 --reset=true";

            CommandResult result = connection.executeCommand(command);

            if (!result.isSuccess()) {
                log.error("执行xray statsquery命令失败: {}", result.getStderr());
                return trafficStatsMap;
            }

            // 解析JSON响应
            String output = result.getStdout();
            if (output == null || output.trim().isEmpty()) {
                log.debug("xray statsquery返回空结果");
                return trafficStatsMap;
            }
            
            Map<String, TrafficStats> userTrafficMap = parseXrayStatsQueryOutput(output);
            trafficStatsMap.putAll(userTrafficMap);
            
            log.debug("成功获取 {} 个用户的流量统计", userTrafficMap.size());
            
        } catch (Exception e) {
            log.error("获取xray流量统计失败: {}", e.getMessage());
        }
        
        return trafficStatsMap;
    }
    
    /**
     * 解析xray statsquery命令输出
     * 输出格式为: {"stat": [{"name": "user>>>username>>>traffic>>>uplink", "value": 173163}, {"name": "inbound>>>default-api>>>traffic>>>downlink", "value": 173163}...]}
     */
    private Map<String, TrafficStats> parseXrayStatsQueryOutput(String output) {
        Map<String, TrafficStats> userTrafficMap = new HashMap<>();
        
        if (output == null || output.trim().isEmpty()) {
            return userTrafficMap;
        }
        
        try {
            // 使用JsonUtil解析JSON输出
            Map<String, Object> jsonMap = JsonUtil.toObject(output, Map.class);
            if (jsonMap == null) {
                return userTrafficMap;
            }
            
            // 获取stat数组
            Object statObj = jsonMap.get("stat");
            if (!(statObj instanceof List)) {
                return userTrafficMap;
            }
            
            List<Map<String, Object>> stats = (List<Map<String, Object>>) statObj;
            
            // 用于存储每个用户的上传和下载流量
            Map<String, Long> uplinkMap = new HashMap<>();
            Map<String, Long> downlinkMap = new HashMap<>();
            
            // 遍历所有统计项
            for (Map<String, Object> stat : stats) {
                String name = (String) stat.get("name");
                Object valueObj = stat.get("value");
                
                if (name == null || valueObj == null) {
                    continue;
                }
                
                long value = 0L;
                if (valueObj instanceof Number) {
                    value = ((Number) valueObj).longValue();
                } else if (valueObj instanceof String) {
                    try {
                        value = Long.parseLong((String) valueObj);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                }
                
                // 解析用户流量统计名称格式: user>>>username>>>traffic>>>uplink/downlink
                if (name.contains(">>>traffic>>>")) {
                    if (name.startsWith("user>>>")) {
                        String[] parts = name.split(">>>");
                        if (parts.length >= 4) {
                            String username = parts[1];
                            String trafficType = parts[3];

                            if ("uplink".equals(trafficType)) {
                                uplinkMap.put(username, value);
                            } else if ("downlink".equals(trafficType)) {
                                downlinkMap.put(username, value);
                            }
                        }
                    }
                }
            }
            
            // 合并上传和下载流量数据
            Set<String> allUsers = new HashSet<>();
            allUsers.addAll(uplinkMap.keySet());
            allUsers.addAll(downlinkMap.keySet());
            
            for (String username : allUsers) {
                long uploadBytes = uplinkMap.getOrDefault(username, 0L);
                long downloadBytes = downlinkMap.getOrDefault(username, 0L);
                
                // 只有当流量大于0时才记录
                if (uploadBytes > 0 || downloadBytes > 0) {
                    userTrafficMap.put(username, new TrafficStats(uploadBytes, downloadBytes));
                }
            }
            
        } catch (Exception e) {
            log.warn("解析xray statsquery输出失败: {}", output, e);
        }
        
        return userTrafficMap;
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

    /**
         * 流量统计数据类
         */
        private record TrafficStats(long uploadBytes, long downloadBytes) {
    }

} 
