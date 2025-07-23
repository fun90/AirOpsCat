package com.fun90.airopscat.service.core;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.service.core.registry.CoreManagementStrategyRegistry;
import com.fun90.airopscat.service.core.strategy.CoreManagementStrategy;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

/**
 * 内核管理服务
 */
@Slf4j
@ApplicationScoped
public class CoreManagementService {

    @Inject
    CoreManagementStrategyRegistry strategyRegistry;
    
    @Inject
    SshConnectionService sshConnectionService;

    /**
     * 执行内核管理操作
     */
    public CoreManagementResult executeOperation(String coreType, CoreOperation operation,
                                                 SshConfig sshConfig, Object... params) {
        try {
            CoreManagementStrategy strategy = strategyRegistry.getStrategy(coreType);
            
            try (SshConnection connection = sshConnectionService.createConnection(sshConfig)) {
                CoreManagementResult result = executeOperationInternal(strategy, operation, connection, params);
                result.setServerAddress(sshConfig.getHost());
                return result;
            }
            
        } catch (Exception e) {
            log.error("执行内核操作失败 [{}:{}]: {}", coreType, operation, e.getMessage());
            
            CoreManagementResult result = new CoreManagementResult();
            result.setSuccess(false);
            result.setMessage("操作执行失败: " + e.getMessage());
            result.setOperationTime(LocalDateTime.now());
            result.setOperation(operation.name());
            result.setServerAddress(sshConfig.getHost());
            
            return result;
        }
    }

    private CoreManagementResult executeOperationInternal(CoreManagementStrategy strategy,
                                                         CoreOperation operation,
                                                         SshConnection connection, 
                                                         Object... params) {
        return switch (operation) {
            case START -> strategy.start(connection);
            case STOP -> strategy.stop(connection);
            case RESTART -> strategy.restart(connection);
            case RELOAD -> strategy.reload(connection);
            case STATUS -> strategy.status(connection);
            case VALIDATE_CONFIG -> null;
            case INSTALL -> strategy.install(connection, params);
            case UNINSTALL -> strategy.uninstall(connection);
            case UPDATE -> strategy.update(connection, params);
            case CONFIG -> strategy.config(connection, params);
            case GET_VERSION -> null;
            case GET_LOGS -> null;
            case IS_INSTALLED -> null;
        };
    }
}