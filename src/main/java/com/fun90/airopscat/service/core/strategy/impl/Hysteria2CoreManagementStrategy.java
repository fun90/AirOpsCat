package com.fun90.airopscat.service.core.strategy.impl;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.core.strategy.CoreManagementStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Hysteria2内核管理策略 - 解耦版本
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
    
    @Override
    public CoreManagementResult start(SshConnection connection) {
        return executeSystemctlCommand(connection, "start", "启动Hysteria2服务");
    }
    
    @Override
    public CoreManagementResult stop(SshConnection connection) {
        return executeSystemctlCommand(connection, "stop", "停止Hysteria2服务");
    }
    
    @Override
    public CoreManagementResult restart(SshConnection connection) {
        return executeSystemctlCommand(connection, "restart", "重启Hysteria2服务");
    }
    
    @Override
    public CoreManagementResult reload(SshConnection connection) {
        // Hysteria2 不支持 reload，使用 restart 代替
        log.info("Hysteria2不支持reload操作，使用restart代替");
        CoreManagementResult result = restart(connection);
        result.setOperation("reload");
        result.setMessage("Hysteria2不支持reload，已执行restart操作");
        return result;
    }
    
    @Override
    public CoreManagementResult status(SshConnection connection) {
        return executeSystemctlCommand(connection, "status", "查询Hysteria2服务状态");
    }
    
    @Override
    public CoreManagementResult install(SshConnection connection, Object... params) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation("install");
        result.setTimestamp(LocalDateTime.now());
        
        try {
            // 1. 检查是否已安装
            CommandResult checkResult = connection.executeCommand("which " + BINARY_PATH);
            if (checkResult.isSuccess()) {
                result.setSuccess(true);
                result.setMessage("Hysteria2已经安装");
                return result;
            }
            
            // 2. 下载并安装Hysteria2
            String installScript = """
                #!/bin/bash
                # 创建目录
                sudo mkdir -p /etc/hysteria /var/log/hysteria
                
                # 下载Hysteria2二进制文件
                wget -O /tmp/hysteria https://github.com/apernet/hysteria/releases/latest/download/hysteria-linux-amd64
                
                # 安装到系统路径
                sudo mv /tmp/hysteria /usr/local/bin/hysteria
                sudo chmod +x /usr/local/bin/hysteria
                
                # 创建systemd服务文件
                sudo tee /etc/systemd/system/hysteria-server.service > /dev/null <<EOF
                [Unit]
                Description=Hysteria Server
                After=network.target
                
                [Service]
                Type=simple
                User=nobody
                ExecStart=/usr/local/bin/hysteria server -c /etc/hysteria/config.yaml
                Restart=always
                RestartSec=3
                
                [Install]
                WantedBy=multi-user.target
                EOF
                
                # 重新加载systemd
                sudo systemctl daemon-reload
                sudo systemctl enable hysteria-server
                
                echo "Hysteria2安装完成"
                """;
            
            CommandResult installResult = connection.executeCommand(installScript);
            
            if (installResult.isSuccess()) {
                result.setSuccess(true);
                result.setMessage("Hysteria2安装成功");
                result.setOutput(installResult.getOutput());
            } else {
                result.setSuccess(false);
                result.setMessage("Hysteria2安装失败: " + installResult.getError());
                result.setOutput(installResult.getOutput());
            }
            
        } catch (Exception e) {
            log.error("安装Hysteria2失败", e);
            result.setSuccess(false);
            result.setMessage("安装异常: " + e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public CoreManagementResult uninstall(SshConnection connection) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation("uninstall");
        result.setTimestamp(LocalDateTime.now());
        
        try {
            String uninstallScript = """
                #!/bin/bash
                # 停止服务
                sudo systemctl stop hysteria-server
                sudo systemctl disable hysteria-server
                
                # 删除文件
                sudo rm -f /usr/local/bin/hysteria
                sudo rm -f /etc/systemd/system/hysteria-server.service
                sudo rm -rf /etc/hysteria
                sudo rm -rf /var/log/hysteria
                
                # 重新加载systemd
                sudo systemctl daemon-reload
                
                echo "Hysteria2卸载完成"
                """;
            
            CommandResult uninstallResult = connection.executeCommand(uninstallScript);
            
            if (uninstallResult.isSuccess()) {
                result.setSuccess(true);
                result.setMessage("Hysteria2卸载成功");
                result.setOutput(uninstallResult.getOutput());
            } else {
                result.setSuccess(false);
                result.setMessage("Hysteria2卸载失败: " + uninstallResult.getError());
                result.setOutput(uninstallResult.getOutput());
            }
            
        } catch (Exception e) {
            log.error("卸载Hysteria2失败", e);
            result.setSuccess(false);
            result.setMessage("卸载异常: " + e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public CoreManagementResult update(SshConnection connection, Object... params) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation("update");
        result.setTimestamp(LocalDateTime.now());
        
        try {
            // 停止服务
            stop(connection);
            
            // 备份当前版本
            CommandResult backupResult = connection.executeCommand(
                "sudo cp /usr/local/bin/hysteria /usr/local/bin/hysteria.backup");
            
            // 下载最新版本
            String updateScript = """
                #!/bin/bash
                # 下载最新版本
                wget -O /tmp/hysteria-new https://github.com/apernet/hysteria/releases/latest/download/hysteria-linux-amd64
                
                # 替换二进制文件
                sudo mv /tmp/hysteria-new /usr/local/bin/hysteria
                sudo chmod +x /usr/local/bin/hysteria
                
                echo "Hysteria2更新完成"
                """;
            
            CommandResult updateResult = connection.executeCommand(updateScript);
            
            if (updateResult.isSuccess()) {
                // 重启服务
                restart(connection);
                
                result.setSuccess(true);
                result.setMessage("Hysteria2更新成功");
                result.setOutput(updateResult.getOutput());
            } else {
                // 恢复备份
                connection.executeCommand("sudo mv /usr/local/bin/hysteria.backup /usr/local/bin/hysteria");
                
                result.setSuccess(false);
                result.setMessage("Hysteria2更新失败: " + updateResult.getError());
                result.setOutput(updateResult.getOutput());
            }
            
        } catch (Exception e) {
            log.error("更新Hysteria2失败", e);
            result.setSuccess(false);
            result.setMessage("更新异常: " + e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public CoreManagementResult config(SshConnection connection, Object... params) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation("config");
        result.setTimestamp(LocalDateTime.now());
        
        try {
            if (params.length > 0 && params[0] instanceof String configContent) {
                // 写入配置文件
                connection.writeRemoteFile(CONFIG_PATH, configContent);
                
                // 验证配置文件语法
                CommandResult validateResult = connection.executeCommand(
                    "/usr/local/bin/hysteria server -c " + CONFIG_PATH + " --check");
                
                if (validateResult.isSuccess()) {
                    result.setSuccess(true);
                    result.setMessage("配置文件更新成功");
                    result.setOutput("配置验证通过");
                } else {
                    result.setSuccess(false);
                    result.setMessage("配置文件语法错误: " + validateResult.getError());
                    result.setOutput(validateResult.getOutput());
                }
            } else {
                // 读取当前配置
                String currentConfig = connection.readRemoteFile(CONFIG_PATH);
                result.setSuccess(true);
                result.setMessage("获取配置文件成功");
                result.setOutput(currentConfig);
            }
            
        } catch (Exception e) {
            log.error("配置Hysteria2失败", e);
            result.setSuccess(false);
            result.setMessage("配置异常: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 执行systemctl命令的通用方法
     */
    private CoreManagementResult executeSystemctlCommand(SshConnection connection, String action, String description) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation(action);
        result.setTimestamp(LocalDateTime.now());
        
        try {
            String command = "sudo systemctl " + action + " " + SERVICE_NAME;
            CommandResult commandResult = connection.executeCommand(command);
            
            if (commandResult.isSuccess()) {
                result.setSuccess(true);
                result.setMessage(description + "成功");
                result.setOutput(commandResult.getOutput());
            } else {
                result.setSuccess(false);
                result.setMessage(description + "失败: " + commandResult.getError());
                result.setOutput(commandResult.getOutput());
            }
            
        } catch (Exception e) {
            log.error("{} 执行异常", description, e);
            result.setSuccess(false);
            result.setMessage(description + "异常: " + e.getMessage());
        }
        
        return result;
    }
}