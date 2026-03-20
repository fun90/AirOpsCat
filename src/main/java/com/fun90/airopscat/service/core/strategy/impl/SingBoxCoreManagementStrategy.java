package com.fun90.airopscat.service.core.strategy.impl;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.service.core.strategy.CoreManagementStrategy;
import com.fun90.airopscat.service.ssh.SshConnection;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

@Slf4j
@ApplicationScoped
@SupportedCores(
        value = {"sing-box", "singbox"},
        priority = 1,
        description = "Sing-box core management strategy",
        supportedOS = {"linux", "ubuntu", "centos", "debian"}
)
public class SingBoxCoreManagementStrategy implements CoreManagementStrategy {

    private static final String CORE_TYPE = "sing-box";
    private static final String SERVICE_NAME = "sing-box";
    private static final String BINARY_PATH = "/usr/local/bin/sing-box";
    private static final String CONFIG_PATH = "/etc/sing-box/config.json";

    @Override
    public CoreManagementResult start(SshConnection connection) {
        return executeSystemctlCommand(connection, "start", "Start sing-box service");
    }

    @Override
    public CoreManagementResult stop(SshConnection connection) {
        return executeSystemctlCommand(connection, "stop", "Stop sing-box service");
    }

    @Override
    public CoreManagementResult restart(SshConnection connection) {
        return executeSystemctlCommand(connection, "restart", "Restart sing-box service");
    }

    @Override
    public CoreManagementResult reload(SshConnection connection) {
        return executeSystemctlCommand(connection, "reload", "Reload sing-box service");
    }

    @Override
    public CoreManagementResult status(SshConnection connection) {
        return executeSystemctlCommand(connection, "status", "Query sing-box service status");
    }

    @Override
    public CoreManagementResult install(SshConnection connection, Object... params) {
        throw new UnsupportedOperationException("Sing-box install is not supported in node deployment");
    }

    @Override
    public CoreManagementResult uninstall(SshConnection connection) {
        throw new UnsupportedOperationException("Sing-box uninstall is not supported in node deployment");
    }

    @Override
    public CoreManagementResult update(SshConnection connection, Object... params) {
        throw new UnsupportedOperationException("Sing-box update is not supported in node deployment");
    }

    @Override
    public CoreManagementResult config(SshConnection connection, Object... params) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation("config");
        result.setCoreType(CORE_TYPE);
        result.setOperationTime(LocalDateTime.now());

        try {
            if (params.length > 0) {
                String configContent = params[0].toString();
                String configPath = params.length > 1 && params[1] instanceof String
                        ? (String) params[1]
                        : CONFIG_PATH;

                connection.executeCommand(String.format("mkdir -p %s", parentDirectory(configPath)));
                String backupPath = configPath + ".backup." + System.currentTimeMillis();
                CommandResult backupResult = connection.executeCommand(
                        String.format("cp %s %s", configPath, backupPath));

                if (!backupResult.isSuccess()) {
                    log.warn("Failed to back up sing-box config file: {}", backupResult.getStderr());
                }

                connection.writeRemoteFile(configPath, configContent);

                CommandResult validateResult = connection.executeCommand(
                        String.format("%s check -c %s", BINARY_PATH, configPath));

                if (validateResult.isSuccess()) {
                    result.setSuccess(true);
                    result.setMessage("Config file updated successfully");
                    result.setOutput("Config validation passed");
                } else {
                    if (backupResult.isSuccess()) {
                        connection.executeCommand(String.format("mv %s %s", backupPath, configPath));
                        result.setMessage("Config validation failed and the previous config has been restored: "
                                + validateResult.getStdout());
                    } else {
                        result.setMessage("Config validation failed: " + validateResult.getStdout());
                    }
                    result.setSuccess(false);
                    result.setError(validateResult.getStdout());
                }
            } else {
                String currentConfig = connection.readRemoteFile(CONFIG_PATH);
                result.setSuccess(true);
                result.setMessage("Current config loaded successfully");
                result.setOutput(currentConfig);
            }
        } catch (Exception e) {
            log.error("Configure sing-box failed", e);
            throw new RuntimeException("Configure sing-box failed: " + e.getMessage(), e);
        }

        return result;
    }

    private CoreManagementResult executeSystemctlCommand(SshConnection connection, String action, String description) {
        CoreManagementResult result = new CoreManagementResult();
        result.setOperation(action);
        result.setCoreType(CORE_TYPE);
        result.setOperationTime(LocalDateTime.now());

        try {
            String command = "systemctl " + action + " " + SERVICE_NAME;
            if ("status".equals(action)) {
                command += " --no-pager";
            }

            CommandResult commandResult = connection.executeCommand(command);
            if (commandResult.isSuccess()) {
                result.setSuccess(true);
                result.setMessage(description + " succeeded");
                result.setOutput(commandResult.getStdout());
            } else {
                result.setSuccess(false);
                result.setMessage(description + " failed");
                result.setError(commandResult.getStderr());
                result.setOutput(commandResult.getStdout());
            }
        } catch (Exception e) {
            log.error("{} threw an exception", description, e);
            result.setSuccess(false);
            result.setMessage(description + " failed: " + e.getMessage());
            result.setError(e.getMessage());
        }

        return result;
    }

    private String parentDirectory(String path) {
        int index = path.lastIndexOf('/');
        return index > 0 ? path.substring(0, index) : "/etc/sing-box";
    }
}
