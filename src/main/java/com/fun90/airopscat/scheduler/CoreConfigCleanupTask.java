package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.model.enums.CoreType;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.BarkService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class CoreConfigCleanupTask {
    private static final Set<String> SUPPORTED_CORE_TYPES = Arrays.stream(CoreType.values())
            .map(CoreType::getValue)
            .collect(Collectors.toUnmodifiableSet());

    @Inject
    ServerConfigRepository serverConfigRepository;

    @Inject
    ServerRepository serverRepository;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    ScheduledSupport scheduledSupport;

    @Inject
    BarkService barkService;

    public void cleanupOldCoreConfigBackupFiles() {
        log.info("开始执行定时任务：清理内核配置旧备份文件");

        try {
            List<ServerConfig> coreConfigs = serverConfigRepository.findAll().list().stream()
                    .filter(serverConfig -> isSupportedCoreType(serverConfig.getConfigType()))
                    .toList();
            if (coreConfigs.isEmpty()) {
                log.info("没有找到需要清理的内核配置，任务结束");
                return;
            }

            Map<Long, Server> serverMap = serverRepository.findByIdIn(coreConfigs.stream()
                            .map(ServerConfig::getServerId)
                            .distinct()
                            .toList())
                    .stream()
                    .collect(Collectors.toMap(Server::getId, server -> server));

            log.info("找到 {} 个内核配置，支持的内核类型: {}", coreConfigs.size(), SUPPORTED_CORE_TYPES);

            LocalDate today = LocalDate.now();
            int totalCleaned = 0;
            int successCount = 0;
            int failureCount = 0;

            for (ServerConfig serverConfig : coreConfigs) {
                try {
                    Server server = serverMap.get(serverConfig.getServerId());
                    if (server == null) {
                        log.warn("服务器 {} 不存在，跳过", serverConfig.getServerId());
                        continue;
                    }
                    if (scheduledSupport.isInvalidServer(server, today)) {
                        log.info("服务器 {} 为托管、禁用或过期状态，跳过内核配置清理，configType={}",
                                server.getId(), serverConfig.getConfigType());
                        continue;
                    }

                    String configDir = scheduledSupport.resolveParentDirectory(serverConfig.getPath());
                    if (configDir == null) {
                        log.warn("服务器 {} 的内核配置路径为空，跳过，configType={}",
                                server.getId(), serverConfig.getConfigType());
                        continue;
                    }

                    int cleanedCount = cleanupServerBackupFiles(server, configDir);
                    totalCleaned += cleanedCount;
                    successCount++;

                    log.info("服务器 {} 清理了 {} 个旧备份文件，configType={}",
                            server.getName(), cleanedCount, serverConfig.getConfigType());
                } catch (Exception e) {
                    log.error("处理服务器配置 {} 时发生错误: {}", serverConfig.getId(), e.getMessage(), e);
                    failureCount++;
                }
            }

            log.info("内核配置备份清理完成 - 总共清理: {} 个文件, 成功处理: {} 个配置, 失败: {} 个配置",
                    totalCleaned, successCount, failureCount);

            if (totalCleaned > 0 || failureCount > 0) {
                String message = String.format("清理了 %d 个内核配置旧备份文件，成功处理: %d 个配置，失败: %d 个配置",
                        totalCleaned, successCount, failureCount);
                if (failureCount > 0) {
                    barkService.sendWarningNotification("AirOpsCat 内核配置清理", message);
                } else {
                    barkService.sendInfoNotification("AirOpsCat 内核配置清理", message);
                }
            }
        } catch (Exception e) {
            log.error("执行内核配置清理任务时发生错误", e);
            barkService.sendErrorNotification("AirOpsCat 内核配置清理失败", "执行内核配置清理任务时发生错误: " + e.getMessage());
        }

        log.info("内核配置清理任务执行完成");
    }

    private int cleanupServerBackupFiles(Server server, String configDir) {
        int cleanedCount = 0;

        try (SshConnection connection = sshConnectionService.createConnection(
                scheduledSupport.buildSshConfig(server))) {
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

            String[] backupFiles = output.trim().split("\n");
            log.info("服务器 {} 找到 {} 个旧备份文件需要清理", server.getName(), backupFiles.length);

            for (String backupFile : backupFiles) {
                if (backupFile.trim().isEmpty()) {
                    continue;
                }

                String deleteCommand = String.format("rm -f '%s'", backupFile.trim());
                CommandResult deleteResult = connection.executeCommand(deleteCommand);

                if (deleteResult.isSuccess()) {
                    cleanedCount++;
                    log.debug("删除旧备份文件 {}", backupFile.trim());
                } else {
                    log.warn("删除备份文件 {} 失败: {}", backupFile.trim(), deleteResult.getStderr());
                }
            }
        } catch (Exception e) {
            log.error("清理服务器 {} 备份文件失败: {}", server.getId(), e.getMessage(), e);
        }

        return cleanedCount;
    }

    private boolean isSupportedCoreType(String configType) {
        if (configType == null || configType.isBlank()) {
            return false;
        }
        return SUPPORTED_CORE_TYPES.contains(configType.trim().toLowerCase());
    }
}
