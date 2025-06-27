package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.model.dto.xray.XrayConfig;
import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.setting.inbound.VlessInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.ShadowsocksInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.SocksInboundSetting;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.utils.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 定时任务服务
 * 负责执行各种定时任务，如检查过期账户、重新部署节点等
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledTaskService {

    private final AccountRepository accountRepository;
    private final TagService tagService;
    private final NodeDeploymentService nodeDeploymentService;
    private final BarkService barkService;
    private final ServerConfigRepository serverConfigRepository;
    private final ServerRepository serverRepository;
    private final SshConnectionService sshConnectionService;
    private final AccountTrafficStatsService accountTrafficStatsService;

    /**
     * 每天凌晨5点执行的任务
     * 检查未禁用但已过期的账户，并重新部署相关的节点
     */
    @Scheduled(cron = "0 0 5 * * ?")
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
            Set<Node> nodes = expiredAccounts.stream()
                    .flatMap(account -> {
                        try {
                            return tagService.getAvailableNodesByAccount(account.getId()).stream();
                        } catch (Exception e) {
                            log.error("获取账户 {} 关联节点时发生错误: {}", account.getId(), e.getMessage());
                            return java.util.stream.Stream.<Node>empty();
                        }
                    })
                    .collect(Collectors.toSet());
            
            if (nodes.isEmpty()) {
                log.info("过期账户没有关联的节点，任务结束");
                return;
            }
            
            log.info("需要重新部署的节点数量: {}", nodes.size());
            
            // 3. 批量重新部署节点
            List<DeploymentResult> deploymentResults = nodeDeploymentService.deployNodesForcibly(
                    nodes.stream().toList()
            );

            // 4. 批量禁用过期账户
            accountRepository.disableExpiredAccounts(
                    expiredAccounts.stream().map(Account::getId).collect(Collectors.toList()),
                    LocalDateTime.now()
            );
            
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
    @Scheduled(fixedRate = 60 * 1000) // 15分钟 = 15 * 60 * 1000毫秒
    @Transactional
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
            
            // 2. 统计周期时间范围
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime periodStart = now.minusMinutes(15); // 前15分钟
            LocalDateTime periodEnd = now;
            
            int successCount = 0;
            int failureCount = 0;
            
            // 3. 遍历每个Xray配置
            for (ServerConfig serverConfig : xrayConfigs) {
                try {
                    // 获取服务器信息
                    Server server = serverRepository.findById(serverConfig.getServerId()).orElse(null);
                    if (server == null) {
                        log.warn("服务器 {} 不存在，跳过", serverConfig.getServerId());
                        continue;
                    }
                    
                    // 解析Xray配置
                    XrayConfig xrayConfig = JsonUtil.toObject(serverConfig.getConfig(), XrayConfig.class);
                    if (xrayConfig == null || xrayConfig.getInbounds() == null) {
                        log.warn("Xray配置解析失败，跳过服务器 {}", server.getId());
                        continue;
                    }
                    
                    // 收集该服务器上所有用户的流量统计
                    int serverSuccessCount = collectServerTrafficStats(server, xrayConfig, periodStart, periodEnd);
                    successCount += serverSuccessCount;
                    
                } catch (Exception e) {
                    log.error("处理服务器配置 {} 时发生错误: {}", serverConfig.getId(), e.getMessage());
                    failureCount++;
                }
            }
            
            log.info("流量统计收集完成 - 成功处理: {} 个配置, 失败: {} 个配置", successCount, failureCount);
            
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
     * 收集单个服务器的流量统计
     */
    private int collectServerTrafficStats(Server server, XrayConfig xrayConfig, 
                                        LocalDateTime periodStart, LocalDateTime periodEnd) {
        int successCount = 0;
        
        try {
            // 创建SSH连接
            SshConfig sshConfig = createSshConfig(server);
            
            try (SshConnection connection = sshConnectionService.createConnection(sshConfig)) {
                
                // 遍历所有入站配置
                for (InboundConfig inbound : xrayConfig.getInbounds()) {
                    if (inbound.getSettings() == null) {
                        continue;
                    }
                    
                    // 根据协议类型处理不同的客户端配置
                    List<String> userEmails = extractUserEmailsFromInbound(inbound);
                    
                    for (String userEmail : userEmails) {
                        try {
                            // 通过xray api获取用户流量统计
                            TrafficStats trafficStats = getXrayUserTrafficStats(connection, userEmail);
                            
                            if (trafficStats != null) {
                                // 查找对应的账户
                                Optional<Account> accountOpt = accountRepository.findByAccountNo(userEmail);
                                if (accountOpt.isPresent()) {
                                    Account account = accountOpt.get();
                                    
                                    // 保存流量统计
                                    AccountTrafficStats stats = new AccountTrafficStats();
                                    stats.setUserId(account.getUserId());
                                    stats.setAccountId(account.getId());
                                    stats.setPeriodStart(periodStart);
                                    stats.setPeriodEnd(periodEnd);
                                    stats.setUploadBytes(trafficStats.getUploadBytes());
                                    stats.setDownloadBytes(trafficStats.getDownloadBytes());
                                    
                                    accountTrafficStatsService.saveStats(stats);
                                    successCount++;
                                    
                                    log.debug("保存用户 {} 流量统计: 上传 {} 字节, 下载 {} 字节", 
                                        userEmail, trafficStats.getUploadBytes(), trafficStats.getDownloadBytes());
                                } else {
                                    log.warn("未找到用户邮箱 {} 对应的账户", userEmail);
                                }
                            }
                            
                        } catch (Exception e) {
                            log.error("获取用户 {} 流量统计失败: {}", userEmail, e.getMessage());
                        }
                    }
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
        
        if (inbound.getSettings() instanceof VlessInboundSetting) {
            VlessInboundSetting vlessSettings = (VlessInboundSetting) inbound.getSettings();
            if (vlessSettings.getClients() != null) {
                for (VlessInboundSetting.VlessClient client : vlessSettings.getClients()) {
                    if (client.getEmail() != null && !client.getEmail().trim().isEmpty()) {
                        userEmails.add(client.getEmail());
                    }
                }
            }
        } else if (inbound.getSettings() instanceof ShadowsocksInboundSetting) {
            ShadowsocksInboundSetting ssSettings = (ShadowsocksInboundSetting) inbound.getSettings();
            if (ssSettings.getClients() != null) {
                for (ShadowsocksInboundSetting.ShadowsocksClient client : ssSettings.getClients()) {
                    if (client.getEmail() != null && !client.getEmail().trim().isEmpty()) {
                        userEmails.add(client.getEmail());
                    }
                }
            }
        } else if (inbound.getSettings() instanceof SocksInboundSetting) {
            SocksInboundSetting socksSettings = (SocksInboundSetting) inbound.getSettings();
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
     * 通过xray api获取用户流量统计
     */
    private TrafficStats getXrayUserTrafficStats(SshConnection connection, String userEmail) {
        try {
            // 构建xray api命令
            String uplinkCommand = String.format("xray api stats --server=127.0.0.1:100 -name 'user>>>%s>>>traffic>>>uplink'", userEmail);
            String downlinkCommand = String.format("xray api stats --server=127.0.0.1:100 -name 'user>>>%s>>>traffic>>>downlink'", userEmail);
            
            // 执行命令获取上传流量
            CommandResult uplinkResult = connection.executeCommand(uplinkCommand);
            long uploadBytes = parseXrayStatsOutput(uplinkResult.getStdout());
            
            // 执行命令获取下载流量
            CommandResult downlinkResult = connection.executeCommand(downlinkCommand);
            long downloadBytes = parseXrayStatsOutput(downlinkResult.getStdout());
            
            return new TrafficStats(uploadBytes, downloadBytes);
            
        } catch (Exception e) {
            log.error("获取用户 {} 流量统计失败: {}", userEmail, e.getMessage());
            return null;
        }
    }
    
    /**
     * 解析xray stats命令输出
     * 输出格式通常为: {"stat": { "name": "user>>>dfaafe11>>>traffic>>>uplink", "value": 173163 }}
     */
    private long parseXrayStatsOutput(String output) {
        if (output == null || output.trim().isEmpty()) {
            return 0L;
        }
        
        try {
            // 使用JsonUtil解析JSON输出
            Map<String, Object> jsonMap = JsonUtil.toObject(output, Map.class);
            if (jsonMap == null) {
                return 0L;
            }
            
            // 获取stat对象
            Map<String, Object> stat = (Map<String, Object>) jsonMap.get("stat");
            if (stat == null) {
                return 0L;
            }
            
            // 获取value值
            Object value = stat.get("value");
            
            if (value instanceof Number) {
                return ((Number) value).longValue();
            } else if (value instanceof String) {
                return Long.parseLong((String) value);
            }
            
            return 0L;
        } catch (Exception e) {
            log.warn("解析xray stats输出失败: {}", output, e);
            return 0L;
        }
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
     * 流量统计数据类
     */
    private static class TrafficStats {
        private final long uploadBytes;
        private final long downloadBytes;
        
        public TrafficStats(long uploadBytes, long downloadBytes) {
            this.uploadBytes = uploadBytes;
            this.downloadBytes = downloadBytes;
        }
        
        public long getUploadBytes() {
            return uploadBytes;
        }
        
        public long getDownloadBytes() {
            return downloadBytes;
        }
    }
} 