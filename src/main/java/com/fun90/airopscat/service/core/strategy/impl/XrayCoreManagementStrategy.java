package com.fun90.airopscat.service.core.strategy.impl;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.service.core.strategy.CoreManagementStrategy;
import com.fun90.airopscat.service.ssh.SshConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Xray内核管理策略
 */
@Slf4j
@Component
@SupportedCores(
    value = {"xray", "xray-core"}, 
    priority = 1, 
    description = "Xray内核管理策略",
    supportedOS = {"linux", "ubuntu", "centos", "debian"}
)
public class XrayCoreManagementStrategy implements CoreManagementStrategy {
    
    private static final String SERVICE_NAME = "xray";
    private static final String BINARY_PATH = "/usr/local/bin/xray";
    private static final String CONFIG_PATH = "/usr/local/etc/xray/config.json";
    private static final String LOG_PATH = "/var/log/xray/";
    private static final String SYSTEMD_SERVICE_PATH = "/etc/systemd/system/xray.service";
    
    @Override
    public CoreManagementResult start(SshConnection connection) {
        return executeSystemctlCommand(connection, "start", "启动Xray服务");
    }
    
    @Override
    public CoreManagementResult stop(SshConnection connection) {
        return executeSystemctlCommand(connection, "stop", "停止Xray服务");
    }
    
    @Override
    public CoreManagementResult restart(SshConnection connection) {
        return executeSystemctlCommand(connection, "restart", "重启Xray服务");
    }
    
    @Override
    public CoreManagementResult reload(SshConnection connection) {
        return executeSystemctlCommand(connection, "reload", "重新加载Xray配置");
    }
    
    @Override
    public CoreManagementResult status(SshConnection connection) {
        return executeSystemctlCommand(connection, "status", "查询Xray服务状态");
    }
    
    @Override
    public CoreManagementResult install(SshConnection connection, Object... params) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation("install");
        result.setCoreType("xray");
        result.setOperationTime(LocalDateTime.now());
        
        try {
            // 1. 检查是否已安装
            CommandResult checkResult = connection.executeCommand("which " + BINARY_PATH);
            if (checkResult.isSuccess()) {
                result.setSuccess(true);
                result.setMessage("Xray已经安装");
                result.setOutput("Xray binary found at: " + BINARY_PATH);
                return result;
            }
            
            // 2. 检查系统架构和操作系统
            CommandResult archResult = connection.executeCommand("uname -m");
            CommandResult osResult = connection.executeCommand("cat /etc/os-release | grep '^ID=' | cut -d'=' -f2 | tr -d '\"'");
            
            if (!archResult.isSuccess() || !osResult.isSuccess()) {
                result.setSuccess(false);
                result.setMessage("无法检测系统信息");
                result.setError("Architecture or OS detection failed");
                return result;
            }
            
            // 3. 执行安装
            String version = params.length > 0 && params[0] instanceof String ? (String) params[0] : null;
            String installCommand = buildInstallCommand(version);
            
            log.info("开始安装Xray: {}", installCommand);
            CommandResult installResult = connection.executeCommand(installCommand);
            
            if (installResult.isSuccess()) {
                // 4. 验证安装
                CommandResult verifyResult = connection.executeCommand(BINARY_PATH + " version");
                if (verifyResult.isSuccess()) {
                    result.setSuccess(true);
                    result.setMessage("Xray安装成功");
                    result.setOutput(verifyResult.getStdout());
                } else {
                    result.setSuccess(false);
                    result.setMessage("Xray安装完成但验证失败");
                    result.setError(verifyResult.getStderr());
                }
            } else {
                result.setSuccess(false);
                result.setMessage("Xray安装失败");
                result.setError(installResult.getStderr());
                result.setOutput(installResult.getStdout());
            }
            
        } catch (Exception e) {
            log.error("安装Xray失败", e);
            result.setSuccess(false);
            result.setMessage("安装异常: " + e.getMessage());
            result.setError(e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public CoreManagementResult uninstall(SshConnection connection) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation("uninstall");
        result.setCoreType("xray");
        result.setOperationTime(LocalDateTime.now());
        
        try {
            // 1. 停止服务
            log.info("停止Xray服务...");
            executeSystemctlCommand(connection, "stop", "停止Xray服务");
            
            // 2. 禁用服务
            connection.executeCommand("sudo systemctl disable " + SERVICE_NAME);
            
            // 3. 使用官方卸载脚本
            String uninstallCommand = "bash -c \"$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)\" @ remove";
            CommandResult uninstallResult = connection.executeCommand(uninstallCommand);
            
            if (uninstallResult.isSuccess()) {
                // 4. 验证卸载
                CommandResult verifyResult = connection.executeCommand("which " + BINARY_PATH);
                if (!verifyResult.isSuccess()) {
                    result.setSuccess(true);
                    result.setMessage("Xray卸载成功");
                    result.setOutput(uninstallResult.getStdout());
                } else {
                    result.setSuccess(false);
                    result.setMessage("Xray卸载完成但文件仍存在");
                    result.setError("Binary still exists after uninstall");
                }
            } else {
                result.setSuccess(false);
                result.setMessage("Xray卸载失败");
                result.setError(uninstallResult.getStderr());
                result.setOutput(uninstallResult.getStdout());
            }
            
        } catch (Exception e) {
            log.error("卸载Xray失败", e);
            result.setSuccess(false);
            result.setMessage("卸载异常: " + e.getMessage());
            result.setError(e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public CoreManagementResult update(SshConnection connection, Object... params) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation("update");
        result.setCoreType("xray");
        result.setOperationTime(LocalDateTime.now());
        
        try {
            // 1. 获取当前版本
            CommandResult currentVersionResult = connection.executeCommand(BINARY_PATH + " version");
            String currentVersion = currentVersionResult.isSuccess() ? 
                parseVersionFromOutput(currentVersionResult.getStdout()) : "unknown";
            
            // 2. 执行更新
            String targetVersion = params.length > 0 && params[0] instanceof String ? (String) params[0] : null;
            String updateCommand = buildInstallCommand(targetVersion); // 使用安装脚本更新
            
            log.info("开始更新Xray从版本 {} 到 {}", currentVersion, targetVersion != null ? targetVersion : "latest");
            CommandResult updateResult = connection.executeCommand(updateCommand);
            
            if (updateResult.isSuccess()) {
                // 3. 验证更新
                CommandResult newVersionResult = connection.executeCommand(BINARY_PATH + " version");
                if (newVersionResult.isSuccess()) {
                    String newVersion = parseVersionFromOutput(newVersionResult.getStdout());
                    result.setSuccess(true);
                    result.setMessage(String.format("Xray更新成功: %s -> %s", currentVersion, newVersion));
                    result.setOutput(newVersionResult.getStdout());
                } else {
                    result.setSuccess(false);
                    result.setMessage("Xray更新完成但版本验证失败");
                    result.setError(newVersionResult.getStderr());
                }
            } else {
                result.setSuccess(false);
                result.setMessage("Xray更新失败");
                result.setError(updateResult.getStderr());
                result.setOutput(updateResult.getStdout());
            }
            
        } catch (Exception e) {
            log.error("更新Xray失败", e);
            result.setSuccess(false);
            result.setMessage("更新异常: " + e.getMessage());
            result.setError(e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public CoreManagementResult config(SshConnection connection, Object... params) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation("config");
        result.setCoreType("xray");
        result.setOperationTime(LocalDateTime.now());
        
        try {
            if (params.length > 0) {
                // 更新配置
                String configContent = params[0].toString();
                String configPath = params.length > 1 && params[1] instanceof String ? 
                    (String) params[1] : CONFIG_PATH;
                
                // 1. 备份现有配置
                String backupPath = configPath + ".backup." + System.currentTimeMillis();
                CommandResult backupResult = connection.executeCommand(
                    String.format("sudo cp %s %s", configPath, backupPath));
                
                if (!backupResult.isSuccess()) {
                    log.warn("无法备份配置文件: {}", backupResult.getStderr());
                }
                
                // 2. 写入新配置
                connection.writeRemoteFile(configPath, configContent);
                
                // 3. 验证配置
                CommandResult validateResult = connection.executeCommand(
                    String.format("sudo %s run -test -config %s", BINARY_PATH, configPath));
                
                if (validateResult.isSuccess()) {
                    result.setSuccess(true);
                    result.setMessage("配置文件更新成功");
                    result.setOutput("配置验证通过");
                } else {
                    // 恢复备份
                    if (backupResult.isSuccess()) {
                        connection.executeCommand(String.format("sudo mv %s %s", backupPath, configPath));
                        result.setMessage("配置文件验证失败，已恢复原配置: " + validateResult.getStdout());
                    } else {
                        result.setMessage("配置文件验证失败: " + validateResult.getStdout());
                    }
                    result.setSuccess(false);
                    result.setError(validateResult.getStdout());
                }
            } else {
                // 读取当前配置
                String currentConfig = connection.readRemoteFile(CONFIG_PATH);
                result.setSuccess(true);
                result.setMessage("获取配置文件成功");
                result.setOutput(currentConfig);
            }
            
        } catch (Exception e) {
            log.error("配置Xray失败", e);
            result.setSuccess(false);
            result.setMessage("配置异常: " + e.getMessage());
            result.setError(e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取Xray版本信息
     */
    public CoreManagementResult getVersion(SshConnection connection) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation("version");
        result.setCoreType("xray");
        result.setOperationTime(LocalDateTime.now());
        
        try {
            CommandResult versionResult = connection.executeCommand(BINARY_PATH + " version");
            
            if (versionResult.isSuccess()) {
                result.setSuccess(true);
                result.setMessage("获取版本信息成功");
                result.setOutput(versionResult.getStdout());
            } else {
                result.setSuccess(false);
                result.setMessage("获取版本信息失败");
                result.setError(versionResult.getStderr());
            }
            
        } catch (Exception e) {
            log.error("获取Xray版本失败", e);
            result.setSuccess(false);
            result.setMessage("获取版本异常: " + e.getMessage());
            result.setError(e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取Xray日志
     */
    public CoreManagementResult getLogs(SshConnection connection, int lines) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation("logs");
        result.setCoreType("xray");
        result.setOperationTime(LocalDateTime.now());
        
        try {
            String command = String.format("sudo journalctl -u %s -n %d --no-pager", SERVICE_NAME, lines);
            CommandResult logResult = connection.executeCommand(command);
            
            if (logResult.isSuccess()) {
                result.setSuccess(true);
                result.setMessage("获取日志成功");
                result.setOutput(logResult.getStdout());
            } else {
                result.setSuccess(false);
                result.setMessage("获取日志失败");
                result.setError(logResult.getStderr());
            }
            
        } catch (Exception e) {
            log.error("获取Xray日志失败", e);
            result.setSuccess(false);
            result.setMessage("获取日志异常: " + e.getMessage());
            result.setError(e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 检查Xray是否已安装
     */
    public CoreManagementResult isInstalled(SshConnection connection) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation("is_installed");
        result.setCoreType("xray");
        result.setOperationTime(LocalDateTime.now());
        
        try {
            CommandResult checkResult = connection.executeCommand("which " + BINARY_PATH);
            boolean installed = checkResult.isSuccess();
            
            result.setSuccess(true); // 检查操作本身成功
            result.setMessage(installed ? "Xray已安装" : "Xray未安装");
            result.setOutput(installed ? "Binary found at: " + BINARY_PATH : "Binary not found");
            
            // 在metadata中存储安装状态
            result.setMetadata(Map.of("installed", installed));
            
        } catch (Exception e) {
            log.error("检查Xray安装状态失败", e);
            result.setSuccess(false);
            result.setMessage("检查安装状态异常: " + e.getMessage());
            result.setError(e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 验证Xray配置文件
     */
    public CoreManagementResult validateConfig(SshConnection connection, String configPath) {
        if (configPath == null || configPath.trim().isEmpty()) {
            configPath = CONFIG_PATH;
        }
        
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation("validate");
        result.setCoreType("xray");
        result.setOperationTime(LocalDateTime.now());
        
        try {
            String command = String.format("sudo %s run -test -config %s", BINARY_PATH, configPath);
            CommandResult validateResult = connection.executeCommand(command);
            
            if (validateResult.isSuccess()) {
                result.setSuccess(true);
                result.setMessage("配置验证通过");
                result.setOutput(validateResult.getStdout());
            } else {
                result.setSuccess(false);
                result.setMessage("配置验证失败");
                result.setError(validateResult.getStderr());
                result.setOutput(validateResult.getStdout());
            }
            
        } catch (Exception e) {
            log.error("验证Xray配置失败", e);
            result.setSuccess(false);
            result.setMessage("配置验证异常: " + e.getMessage());
            result.setError(e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 执行systemctl命令的通用方法
     */
    private CoreManagementResult executeSystemctlCommand(SshConnection connection, String action, String description) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation(action);
        result.setCoreType("xray");
        result.setOperationTime(LocalDateTime.now());
        
        try {
            String command = "sudo systemctl " + action + " " + SERVICE_NAME;
            if ("status".equals(action)) {
                command += " --no-pager"; // 避免分页输出
            }
            
            CommandResult commandResult = connection.executeCommand(command);
            
            if (commandResult.isSuccess()) {
                result.setSuccess(true);
                result.setMessage(description + "成功");
                result.setOutput(commandResult.getStdout());
            } else {
                result.setSuccess(false);
                result.setMessage(description + "失败");
                result.setError(commandResult.getStderr());
                result.setOutput(commandResult.getStdout());
            }
            
        } catch (Exception e) {
            log.error("{} 执行异常", description, e);
            result.setSuccess(false);
            result.setMessage(description + "异常: " + e.getMessage());
            result.setError(e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 构建安装命令
     */
    private String buildInstallCommand(String version) {
        if (version != null && !version.trim().isEmpty()) {
            return String.format("bash -c \"$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)\" @ install --version %s", version);
        } else {
            return "bash -c \"$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)\" @ install";
        }
    }
    
    /**
     * 从版本输出中解析版本号
     */
    private String parseVersionFromOutput(String versionOutput) {
        if (versionOutput == null || versionOutput.trim().isEmpty()) {
            return "unknown";
        }
        
        // Xray版本输出通常格式为: "Xray 1.8.4 (Xray, Penetrates Everything.) Custom (go1.21.0 linux/amd64)"
        // 提取版本号
        String[] lines = versionOutput.split("\n");
        for (String line : lines) {
            if (line.contains("Xray") && line.matches(".*\\d+\\.\\d+\\.\\d+.*")) {
                String[] parts = line.split("\\s+");
                for (String part : parts) {
                    if (part.matches("\\d+\\.\\d+\\.\\d+")) {
                        return part;
                    }
                }
            }
        }
        
        return versionOutput.trim();
    }
}