package com.fun90.airopscat.service.core.strategy;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.service.ssh.SshConnection;

/**
 * 内核管理策略接口 - 解耦版本
 */
public interface CoreManagementStrategy {

    CoreManagementResult start(SshConnection connection);

    CoreManagementResult stop(SshConnection connection);

    CoreManagementResult restart(SshConnection connection);

    CoreManagementResult reload(SshConnection connection);

    CoreManagementResult status(SshConnection connection);

    CoreManagementResult install(SshConnection connection, Object... params);

    CoreManagementResult uninstall(SshConnection connection);

    CoreManagementResult update(SshConnection connection, Object... params);

    CoreManagementResult config(SshConnection connection, Object... params);
}