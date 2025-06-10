package com.fun90.airopscat.service.core.strategy.impl;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.service.SshService;
import com.fun90.airopscat.service.core.strategy.CoreManagementStrategy;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
    
    @Autowired
    private SshService sshService;
    
    @Override
    public CoreManagementResult start(Session session) {
        return executeSystemctlCommand(session, "start", "启动Xray服务");
    }
    
    @Override
    public CoreManagementResult stop(Session session) {
        return executeSystemctlCommand(session, "stop", "停止Xray服务");
    }
    
    @Override
    public CoreManagementResult restart(Session session) {
        return executeSystemctlCommand(session, "restart", "重启Xray服务");
    }
    
    @Override
    public CoreManagementResult reload(Session session) {
        return executeSystemctlCommand(session, "reload", "重新加载Xray配置");
    }
    
    @Override
    public CoreManagementResult status(Session session) {
        try {
            long startTime = System.currentTimeMillis();
            CommandResult result = sshService.executeCommand(session, 
                "sudo systemctl status " + SERVICE_NAME + " --no-pager");
            long duration = System.currentTimeMillis() - startTime;
            
            CoreManagementResult coreResult = new CoreManagementResult();
            coreResult.setOperation("status");
            coreResult.setCoreType("xray");
            coreResult.setServerAddress(session.getHost());
            coreResult.setSuccess(result.getExitStatus() == 0);
            coreResult.setOutput(result.getStdout());
            coreResult.setError(result.getStderr());
            coreResult.setExitCode(result.getExitStatus());
            coreResult.setDuration(duration);
            coreResult.setMessage(coreResult.isSuccess() ? "状态检查成功" : "状态检查失败");
            
            return coreResult;
        } catch (Exception e) {
            log.error("检查Xray状态失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("status", "xray", "状态检查失败", e.getMessage());
        }
    }
    
    @Override
    public CoreManagementResult validateConfig(Session session, String configPath) {
        if (configPath == null || configPath.trim().isEmpty()) {
            configPath = CONFIG_PATH;
        }
        
        try {
            long startTime = System.currentTimeMillis();
            String command = String.format("sudo %s run -test -config %s", BINARY_PATH, configPath);
            CommandResult result = sshService.executeCommand(session, command);
            long duration = System.currentTimeMillis() - startTime;
            
            CoreManagementResult coreResult = new CoreManagementResult();
            coreResult.setOperation("validate");
            coreResult.setCoreType("xray");
            coreResult.setServerAddress(session.getHost());
            coreResult.setSuccess(result.getExitStatus() == 0);
            coreResult.setOutput(result.getStdout());
            coreResult.setError(result.getStderr());
            coreResult.setExitCode(result.getExitStatus());
            coreResult.setDuration(duration);
            coreResult.setMessage(coreResult.isSuccess() ? "配置验证通过" : "配置验证失败");
            
            return coreResult;
        } catch (Exception e) {
            log.error("验证Xray配置失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("validate", "xray", "配置验证失败", e.getMessage());
        }
    }
    
    @Override
    public CoreManagementResult install(Session session, String version) {
        try {
            long startTime = System.currentTimeMillis();
            
            // 使用官方安装脚本
            String command = version != null && !version.trim().isEmpty() 
                ? String.format("bash -c \"$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)\" @ install --version %s", version)
                : "bash -c \"$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)\" @ install";
            
            CommandResult result = sshService.executeCommand(session, command);
            long duration = System.currentTimeMillis() - startTime;
            
            CoreManagementResult coreResult = new CoreManagementResult();
            coreResult.setOperation("install");
            coreResult.setCoreType("xray");
            coreResult.setServerAddress(session.getHost());
            coreResult.setSuccess(result.getExitStatus() == 0);
            coreResult.setOutput(result.getStdout());
            coreResult.setError(result.getStderr());
            coreResult.setExitCode(result.getExitStatus());
            coreResult.setDuration(duration);
            coreResult.setMessage(coreResult.isSuccess() ? "Xray安装成功" : "Xray安装失败");
            
            return coreResult;
        } catch (Exception e) {
            log.error("安装Xray失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("install", "xray", "安装失败", e.getMessage());
        }
    }
    
    @Override
    public CoreManagementResult uninstall(Session session) {
        try {
            long startTime = System.currentTimeMillis();
            String command = "bash -c \"$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)\" @ remove";
            CommandResult result = sshService.executeCommand(session, command);
            long duration = System.currentTimeMillis() - startTime;
            
            CoreManagementResult coreResult = new CoreManagementResult();
            coreResult.setOperation("uninstall");
            coreResult.setCoreType("xray");
            coreResult.setServerAddress(session.getHost());
            coreResult.setSuccess(result.getExitStatus() == 0);
            coreResult.setOutput(result.getStdout());
            coreResult.setError(result.getStderr());
            coreResult.setExitCode(result.getExitStatus());
            coreResult.setDuration(duration);
            coreResult.setMessage(coreResult.isSuccess() ? "Xray卸载成功" : "Xray卸载失败");
            
            return coreResult;
        } catch (Exception e) {
            log.error("卸载Xray失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("uninstall", "xray", "卸载失败", e.getMessage());
        }
    }
    
    @Override
    public CoreManagementResult updateConfig(Session session, String configContent, String configPath) {
        if (configPath == null || configPath.trim().isEmpty()) {
            configPath = CONFIG_PATH;
        }
        
        try {
            long startTime = System.currentTimeMillis();
            
            // 备份原配置
            String backupCommand = String.format("sudo cp %s %s.backup.%d", 
                configPath, configPath, System.currentTimeMillis());
            sshService.executeCommand(session, backupCommand);
            
            // 更新配置文件
            sshService.writeRemoteFile(session, configContent, configPath);
            
            // 验证新配置
            CoreManagementResult validationResult = validateConfig(session, configPath);
            if (!validationResult.isSuccess()) {
                // 恢复备份
                String restoreCommand = String.format("sudo mv %s.backup.* %s", configPath, configPath);
                sshService.executeCommand(session, restoreCommand);
                
                validationResult.setMessage("配置更新失败：新配置验证不通过，已恢复原配置");
                return validationResult;
            }
            
            long duration = System.currentTimeMillis() - startTime;
            
            CoreManagementResult result = CoreManagementResult.success("update_config", "xray", "配置更新成功");
            result.setServerAddress(session.getHost());
            result.setDuration(duration);
            
            return result;
        } catch (Exception e) {
            log.error("更新Xray配置失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("update_config", "xray", "配置更新失败", e.getMessage());
        }
    }
    
    @Override
    public CoreManagementResult getVersion(Session session) {
        try {
            long startTime = System.currentTimeMillis();
            CommandResult result = sshService.executeCommand(session, BINARY_PATH + " version");
            long duration = System.currentTimeMillis() - startTime;
            
            CoreManagementResult coreResult = new CoreManagementResult();
            coreResult.setOperation("version");
            coreResult.setCoreType("xray");
            coreResult.setServerAddress(session.getHost());
            coreResult.setSuccess(result.getExitStatus() == 0);
            coreResult.setOutput(result.getStdout());
            coreResult.setError(result.getStderr());
            coreResult.setExitCode(result.getExitStatus());
            coreResult.setDuration(duration);
            coreResult.setMessage(coreResult.isSuccess() ? "获取版本信息成功" : "获取版本信息失败");
            
            return coreResult;
        } catch (Exception e) {
            log.error("获取Xray版本失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("version", "xray", "获取版本失败", e.getMessage());
        }
    }
    
    @Override
    public CoreManagementResult getLogs(Session session, int lines) {
        try {
            long startTime = System.currentTimeMillis();
            String command = String.format("sudo journalctl -u %s -n %d --no-pager", SERVICE_NAME, lines);
            CommandResult result = sshService.executeCommand(session, command);
            long duration = System.currentTimeMillis() - startTime;
            
            CoreManagementResult coreResult = new CoreManagementResult();
            coreResult.setOperation("logs");
            coreResult.setCoreType("xray");
            coreResult.setServerAddress(session.getHost());
            coreResult.setSuccess(result.getExitStatus() == 0);
            coreResult.setOutput(result.getStdout());
            coreResult.setError(result.getStderr());
            coreResult.setExitCode(result.getExitStatus());
            coreResult.setDuration(duration);
            coreResult.setMessage(coreResult.isSuccess() ? "获取日志成功" : "获取日志失败");
            
            return coreResult;
        } catch (Exception e) {
            log.error("获取Xray日志失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("logs", "xray", "获取日志失败", e.getMessage());
        }
    }
    
    @Override
    public CoreManagementResult isInstalled(Session session) {
        try {
            long startTime = System.currentTimeMillis();
            CommandResult result = sshService.executeCommand(session, "which " + BINARY_PATH);
            long duration = System.currentTimeMillis() - startTime;
            
            boolean installed = result.getExitStatus() == 0;
            
            CoreManagementResult coreResult = new CoreManagementResult();
            coreResult.setOperation("is_installed");
            coreResult.setCoreType("xray");
            coreResult.setServerAddress(session.getHost());
            coreResult.setSuccess(true); // 检查操作本身成功
            coreResult.setOutput(installed ? "Xray已安装" : "Xray未安装");
            coreResult.setExitCode(installed ? 0 : 1);
            coreResult.setDuration(duration);
            coreResult.setMessage("安装状态检查完成");
            
            return coreResult;
        } catch (Exception e) {
            log.error("检查Xray安装状态失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("is_installed", "xray", "检查安装状态失败", e.getMessage());
        }
    }
    
    /**
     * 执行systemctl命令的通用方法
     */
    private CoreManagementResult executeSystemctlCommand(Session session, String action, String description) {
        try {
            long startTime = System.currentTimeMillis();
            String command = String.format("sudo systemctl %s %s", action, SERVICE_NAME);
            CommandResult result = sshService.executeCommand(session, command);
            long duration = System.currentTimeMillis() - startTime;
            
            CoreManagementResult coreResult = new CoreManagementResult();
            coreResult.setOperation(action);
            coreResult.setCoreType("xray");
            coreResult.setServerAddress(session.getHost());
            coreResult.setSuccess(result.getExitStatus() == 0);
            coreResult.setOutput(result.getStdout());
            coreResult.setError(result.getStderr());
            coreResult.setExitCode(result.getExitStatus());
            coreResult.setDuration(duration);
            coreResult.setMessage(coreResult.isSuccess() ? description + "成功" : description + "失败");
            
            // 如果是重启操作，额外进行状态检查
            if ("restart".equals(action) && coreResult.isSuccess()) {
                try {
                    Thread.sleep(2000); // 等待服务完全启动
                    CoreManagementResult statusResult = status(session);
                    if (statusResult.isSuccess() && statusResult.getOutput().contains("active (running)")) {
                        coreResult.setOutput(coreResult.getOutput() + "\n服务状态验证：运行正常");
                    } else {
                        coreResult.setSuccess(false);
                        coreResult.setMessage(description + "完成但服务状态异常");
                        coreResult.setError("服务重启后状态检查失败");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            return coreResult;
        } catch (Exception e) {
            log.error("执行Xray {} 操作失败: {}", action, e.getMessage(), e);
            return CoreManagementResult.failure(action, "xray", description + "失败", e.getMessage());
        }
    }
}