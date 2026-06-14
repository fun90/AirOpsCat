package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.BarkService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@ApplicationScoped
public class CoreConfigCleanupTask {
    static final String SING_BOX_CONFIG_PATH = "/etc/sing-box/config.json";

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
            List<Server> servers = loadRuntimeTargetServers();
            if (servers.isEmpty()) {
                log.info("没有找到需要清理的目标服务器，任务结束");
                return;
            }

            String configDir = scheduledSupport.resolveParentDirectory(SING_BOX_CONFIG_PATH);
            log.info("找到 {} 台需要清理备份的目标服务器", servers.size());
            int totalCleaned = 0;
            int successCount = 0;
            int failureCount = 0;

            for (Server server : servers) {
                try {
                    int cleanedCount = cleanupServerBackupFiles(server, configDir);
                    totalCleaned += cleanedCount;
                    successCount++;

                    log.info("服务器 {} 清理了 {} 个旧备份文件", server.getName(), cleanedCount);
                } catch (Exception e) {
                    log.error("处理服务器 {} 时发生错误: {}", server.getId(), e.getMessage(), e);
                    failureCount++;
                }
            }

            log.info("内核配置备份清理完成 - 总共清理: {} 个文件, 成功处理: {} 台服务器, 失败: {} 台服务器",
                    totalCleaned, successCount, failureCount);

            if (totalCleaned > 0 || failureCount > 0) {
                String message = String.format("清理了 %d 个内核配置旧备份文件，成功处理: %d 台服务器，失败: %d 台服务器",
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

    List<Server> loadRuntimeTargetServers() {
        return serverRepository.findRuntimeTargetServers(LocalDate.now());
    }

    int cleanupServerBackupFiles(Server server, String configDir) {
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
}
