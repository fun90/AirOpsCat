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
 * Hysteria2内核管理策略
 */
@Slf4j
@Component
@SupportedCores(
    value = {"hysteria2", "hysteria", "hy2"}, 
    priority = 1, 
    description = "Hysteria2内核管理策略",
    supportedOS = {"linux", "ubuntu", "centos", "debian"}
)
public class Hysteria2CoreManagementStrategy implements CoreManagementStrategy {
    
    private static final String SERVICE_NAME = "hysteria-server";
    private static final String BINARY_PATH = "/usr/local/bin/hysteria";
    private static final String CONFIG_PATH = "/etc/hysteria/config.yaml";
    private static final String LOG_PATH = "/var/log/hysteria/";
    private static final String SYSTEMD_SERVICE_PATH = "/etc/systemd/system/hysteria-server.service";
    
    @Autowired
    private SshService sshService;
    
    @Override
    public CoreManagementResult start(Session session) {
        return executeSystemctlCommand(session, "start", "启动Hysteria2服务");
    }
    
    @Override
    public CoreManagementResult stop(Session session) {
        return executeSystemctlCommand(session, "stop", "停止Hysteria2服务");
    }
    
    @Override
    public CoreManagementResult restart(Session session) {
        return executeSystemctlCommand(session, "restart", "重启Hysteria2服务");
    }
    
    @Override
    public CoreManagementResult reload(Session session) {
        // Hysteria2 不支持 reload，使用 restart 代替
        log.info("Hysteria2不支持reload操作，使用restart代替");
        CoreManagementResult result = restart(session);
        result.setOperation("reload");
        result.setMessage(result.getMessage().replace("重启", "重新加载"));
        return result;
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
            coreResult.setCoreType("hysteria2");
            coreResult.setServerAddress(session.getHost());
            coreResult.setSuccess(result.getExitStatus() == 0);
            coreResult.setOutput(result.getStdout());
            coreResult.setError(result.getStderr());
            coreResult.setExitCode(result.getExitStatus());
            coreResult.setDuration(duration);
            coreResult.setMessage(coreResult.isSuccess() ? "状态检查成功" : "状态检查失败");
            
            return coreResult;
        } catch (Exception e) {
            log.error("检查Hysteria2状态失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("status", "hysteria2", "状态检查失败", e.getMessage());
        }
    }
    
    @Override
    public CoreManagementResult validateConfig(Session session, String configPath) {
        if (configPath == null || configPath.trim().isEmpty()) {
            configPath = CONFIG_PATH;
        }
        
        try {
            long startTime = System.currentTimeMillis();
            
            // 检查配置文件是否存在
            CommandResult fileCheckResult = sshService.executeCommand(session, 
                String.format("test -f %s && echo 'exists' || echo 'not found'", configPath));
            
            if (fileCheckResult.getStdout().contains("not found")) {
                return CoreManagementResult.failure("validate", "hysteria2", 
                    "配置文件验证失败", "配置文件不存在: " + configPath);
            }
            
            // Hysteria2 配置验证命令
            String command = String.format("sudo %s server -c %s --check", BINARY_PATH, configPath);
            CommandResult result = sshService.executeCommand(session, command);
            long duration = System.currentTimeMillis() - startTime;
            
            CoreManagementResult coreResult = new CoreManagementResult();
            coreResult.setOperation("validate");
            coreResult.setCoreType("hysteria2");
            coreResult.setServerAddress(session.getHost());
            coreResult.setSuccess(result.getExitStatus() == 0);
            coreResult.setOutput(result.getStdout());
            coreResult.setError(result.getStderr());
            coreResult.setExitCode(result.getExitStatus());
            coreResult.setDuration(duration);
            coreResult.setMessage(coreResult.isSuccess() ? "配置验证通过" : "配置验证失败");
            
            return coreResult;
        } catch (Exception e) {
            log.error("验证Hysteria2配置失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("validate", "hysteria2", "配置验证失败", e.getMessage());
        }
    }
    
    @Override
    public CoreManagementResult install(Session session, String version) {
        try {
            long startTime = System.currentTimeMillis();
            
            // 检查是否已安装
            CoreManagementResult installedCheck = isInstalled(session);
            if (installedCheck.getExitCode() == 0) {
                return CoreManagementResult.success("install", "hysteria2", "Hysteria2已经安装");
            }
            
            StringBuilder installScript = new StringBuilder();
            
            // 更新系统包
            installScript.append("sudo apt-get update -y && ");
            
            // 下载并安装 Hysteria2
            if (version != null && !version.trim().isEmpty()) {
                installScript.append(String.format("HY2_VERSION=%s ", version));
            }
            installScript.append("bash <(curl -fsSL https://get.hy2.sh/)");
            
            CommandResult downloadResult = sshService.executeCommand(session, installScript.toString());
            
            if (downloadResult.getExitStatus() != 0) {
                return CoreManagementResult.failure("install", "hysteria2", 
                    "Hysteria2下载安装失败", downloadResult.getStderr());
            }
            
            // 创建配置目录
            sshService.executeCommand(session, "sudo mkdir -p /etc/hysteria");
            sshService.executeCommand(session, "sudo mkdir -p /var/log/hysteria");
            
            // 创建默认配置文件
            String defaultConfig = createDefaultConfig();
            sshService.writeRemoteFile(session, defaultConfig, CONFIG_PATH);
            sshService.executeCommand(session, "sudo chmod 644 " + CONFIG_PATH);
            
            // 创建systemd服务文件
            String serviceContent = createSystemdServiceFile();
            sshService.writeRemoteFile(session, serviceContent, SYSTEMD_SERVICE_PATH);
            sshService.executeCommand(session, "sudo chmod 644 " + SYSTEMD_SERVICE_PATH);
            
            // 重新加载systemd
            sshService.executeCommand(session, "sudo systemctl daemon-reload");
            
            // 启用服务
            sshService.executeCommand(session, "sudo systemctl enable " + SERVICE_NAME);
            
            long duration = System.currentTimeMillis() - startTime;
            
            CoreManagementResult result = CoreManagementResult.success("install", "hysteria2", "Hysteria2安装成功");
            result.setServerAddress(session.getHost());
            result.setDuration(duration);
            result.setOutput("Hysteria2已安装并配置完成\n配置文件路径: " + CONFIG_PATH + 
                           "\n服务文件路径: " + SYSTEMD_SERVICE_PATH);
            
            return result;
        } catch (Exception e) {
            log.error("安装Hysteria2失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("install", "hysteria2", "安装失败", e.getMessage());
        }
    }
    
    @Override
    public CoreManagementResult uninstall(Session session) {
        try {
            long startTime = System.currentTimeMillis();
            
            // 停止并禁用服务
            sshService.executeCommand(session, "sudo systemctl stop " + SERVICE_NAME);
            sshService.executeCommand(session, "sudo systemctl disable " + SERVICE_NAME);
            
            // 删除服务文件
            sshService.executeCommand(session, "sudo rm -f " + SYSTEMD_SERVICE_PATH);
            
            // 重新加载systemd
            sshService.executeCommand(session, "sudo systemctl daemon-reload");
            
            // 删除二进制文件
            sshService.executeCommand(session, "sudo rm -f " + BINARY_PATH);
            
            // 删除配置目录（询问用户是否保留配置）
            sshService.executeCommand(session, "sudo rm -rf /etc/hysteria");
            
            // 删除日志目录
            sshService.executeCommand(session, "sudo rm -rf /var/log/hysteria");
            
            long duration = System.currentTimeMillis() - startTime;
            
            CoreManagementResult result = CoreManagementResult.success("uninstall", "hysteria2", "Hysteria2卸载成功");
            result.setServerAddress(session.getHost());
            result.setDuration(duration);
            result.setOutput("已删除:\n- 二进制文件: " + BINARY_PATH + 
                           "\n- 配置目录: /etc/hysteria" + 
                           "\n- 服务文件: " + SYSTEMD_SERVICE_PATH + 
                           "\n- 日志目录: /var/log/hysteria");
            
            return result;
        } catch (Exception e) {
            log.error("卸载Hysteria2失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("uninstall", "hysteria2", "卸载失败", e.getMessage());
        }
    }
    
    @Override
    public CoreManagementResult updateConfig(Session session, String configContent, String configPath) {
        if (configPath == null || configPath.trim().isEmpty()) {
            configPath = CONFIG_PATH;
        }
        
        try {
            long startTime = System.currentTimeMillis();
            
            // 确保配置目录存在
            sshService.executeCommand(session, "sudo mkdir -p /etc/hysteria");
            
            // 备份原配置
            String timestamp = String.valueOf(System.currentTimeMillis());
            String backupPath = configPath + ".backup." + timestamp;
            CommandResult backupResult = sshService.executeCommand(session, 
                String.format("sudo cp %s %s 2>/dev/null || true", configPath, backupPath));
            
            // 写入新配置
            sshService.writeRemoteFile(session, configContent, configPath);
            sshService.executeCommand(session, "sudo chmod 644 " + configPath);
            
            // 验证新配置
            CoreManagementResult validationResult = validateConfig(session, configPath);
            if (!validationResult.isSuccess()) {
                // 恢复备份
                sshService.executeCommand(session, 
                    String.format("sudo mv %s %s 2>/dev/null || true", backupPath, configPath));
                
                validationResult.setMessage("配置更新失败：新配置验证不通过，已恢复原配置");
                validationResult.setOperation("update_config");
                return validationResult;
            }
            
            // 删除备份文件（可选）
            sshService.executeCommand(session, "sudo rm -f " + backupPath);
            
            long duration = System.currentTimeMillis() - startTime;
            
            CoreManagementResult result = CoreManagementResult.success("update_config", "hysteria2", "配置更新成功");
            result.setServerAddress(session.getHost());
            result.setDuration(duration);
            result.setOutput("配置文件已更新: " + configPath + "\n配置验证通过");
            
            return result;
        } catch (Exception e) {
            log.error("更新Hysteria2配置失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("update_config", "hysteria2", "配置更新失败", e.getMessage());
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
            coreResult.setCoreType("hysteria2");
            coreResult.setServerAddress(session.getHost());
            coreResult.setSuccess(result.getExitStatus() == 0);
            coreResult.setOutput(result.getStdout());
            coreResult.setError(result.getStderr());
            coreResult.setExitCode(result.getExitStatus());
            coreResult.setDuration(duration);
            coreResult.setMessage(coreResult.isSuccess() ? "获取版本信息成功" : "获取版本信息失败");
            
            return coreResult;
        } catch (Exception e) {
            log.error("获取Hysteria2版本失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("version", "hysteria2", "获取版本失败", e.getMessage());
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
            coreResult.setCoreType("hysteria2");
            coreResult.setServerAddress(session.getHost());
            coreResult.setSuccess(result.getExitStatus() == 0);
            coreResult.setOutput(result.getStdout());
            coreResult.setError(result.getStderr());
            coreResult.setExitCode(result.getExitStatus());
            coreResult.setDuration(duration);
            coreResult.setMessage(coreResult.isSuccess() ? "获取日志成功" : "获取日志失败");
            
            return coreResult;
        } catch (Exception e) {
            log.error("获取Hysteria2日志失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("logs", "hysteria2", "获取日志失败", e.getMessage());
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
            coreResult.setCoreType("hysteria2");
            coreResult.setServerAddress(session.getHost());
            coreResult.setSuccess(true); // 检查操作本身成功
            coreResult.setOutput(installed ? "Hysteria2已安装" : "Hysteria2未安装");
            coreResult.setExitCode(installed ? 0 : 1);
            coreResult.setDuration(duration);
            coreResult.setMessage("安装状态检查完成");
            
            // 如果已安装，获取更多信息
            if (installed) {
                try {
                    CoreManagementResult versionResult = getVersion(session);
                    if (versionResult.isSuccess()) {
                        coreResult.setOutput(coreResult.getOutput() + "\n版本信息: " + versionResult.getOutput().trim());
                    }
                    
                    // 检查配置文件是否存在
                    CommandResult configCheck = sshService.executeCommand(session, "test -f " + CONFIG_PATH);
                    if (configCheck.getExitStatus() == 0) {
                        coreResult.setOutput(coreResult.getOutput() + "\n配置文件: " + CONFIG_PATH + " (存在)");
                    } else {
                        coreResult.setOutput(coreResult.getOutput() + "\n配置文件: " + CONFIG_PATH + " (不存在)");
                    }
                    
                    // 检查服务状态
                    CommandResult serviceCheck = sshService.executeCommand(session, 
                        "sudo systemctl is-enabled " + SERVICE_NAME + " 2>/dev/null || echo 'not-enabled'");
                    coreResult.setOutput(coreResult.getOutput() + "\n服务状态: " + serviceCheck.getStdout().trim());
                } catch (Exception e) {
                    log.debug("获取附加信息时出错: {}", e.getMessage());
                }
            }
            
            return coreResult;
        } catch (Exception e) {
            log.error("检查Hysteria2安装状态失败: {}", e.getMessage(), e);
            return CoreManagementResult.failure("is_installed", "hysteria2", "检查安装状态失败", e.getMessage());
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
            coreResult.setCoreType("hysteria2");
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
                    Thread.sleep(3000); // Hysteria2启动可能需要更多时间
                    CoreManagementResult statusResult = status(session);
                    if (statusResult.isSuccess() && statusResult.getOutput().contains("active (running)")) {
                        coreResult.setOutput(coreResult.getOutput() + "\n服务状态验证：运行正常");
                    } else {
                        coreResult.setSuccess(false);
                        coreResult.setMessage(description + "完成但服务状态异常");
                        coreResult.setError("服务重启后状态检查失败: " + statusResult.getError());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            return coreResult;
        } catch (Exception e) {
            log.error("执行Hysteria2 {} 操作失败: {}", action, e.getMessage(), e);
            return CoreManagementResult.failure(action, "hysteria2", description + "失败", e.getMessage());
        }
    }
    
    /**
     * 创建默认配置文件内容
     */
    private String createDefaultConfig() {
        return """
listen: :443

tls:
  cert: /path/to/cert.pem
  key: /path/to/key.pem

auth:
  type: password
  password: your_password_here

masquerade:
  type: proxy
  proxy:
    url: https://www.bing.com
    rewriteHost: true

bandwidth:
  up: 1 gbps
  down: 1 gbps

ignoreClientBandwidth: false

udpIdleTimeout: 60s
udpHopInterval: 30s

# Quic settings
quic:
  initStreamReceiveWindow: 8388608
  maxStreamReceiveWindow: 8388608
  initConnReceiveWindow: 20971520
  maxConnReceiveWindow: 20971520
  maxIdleTimeout: 30s
  maxIncomingStreams: 1024
  disablePathMTUDiscovery: false

# Logging
log:
  level: info
  output: /var/log/hysteria/hysteria.log
  maxSize: 10
  maxBackups: 3
  maxAge: 7
  compress: true
""";
    }
    
    /**
     * 创建systemd服务文件内容
     */
    private String createSystemdServiceFile() {
        return String.format("""
[Unit]
Description=Hysteria Server Service
Documentation=https://hysteria.network
After=network.target nss-lookup.target
Wants=network.target

[Service]
Type=simple
User=hysteria
Group=hysteria
ExecStart=%s server -c %s
Restart=on-failure
RestartSec=10
RestartPreventExitStatus=23
LimitNPROC=10000
LimitNOFILE=1000000

# Security settings
NoNewPrivileges=true
PrivateTmp=true
ProtectProc=invisible
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/log/hysteria
CapabilityBoundingSet=CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
""", BINARY_PATH, CONFIG_PATH);
    }
    
    /**
     * 验证参数
     */
    @Override
    public boolean validateParams(String operation, Object... params) {
        switch (operation) {
            case "validate":
            case "update_config":
                return params != null && params.length > 0;
            case "install":
                // version 参数是可选的
                return true;
            case "logs":
                if (params != null && params.length > 0) {
                    try {
                        int lines = (Integer) params[0];
                        return lines > 0 && lines <= 10000; // 限制日志行数
                    } catch (Exception e) {
                        return false;
                    }
                }
                return true;
            default:
                return true;
        }
    }
}