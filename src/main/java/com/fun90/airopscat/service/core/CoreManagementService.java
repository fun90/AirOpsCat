package com.fun90.airopscat.service.core;

import com.fun90.airopscat.model.dto.BatchCoreManagementResult;
import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.service.core.registry.CoreManagementStrategyRegistry;
import com.fun90.airopscat.service.core.strategy.CoreManagementStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内核管理服务 - 解耦版本
 */
@Slf4j
@Service
public class CoreManagementService {

    private final CoreManagementStrategyRegistry strategyRegistry;
    private final SshConnectionService sshConnectionService;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @Autowired
    public CoreManagementService(CoreManagementStrategyRegistry strategyRegistry, 
                                SshConnectionService sshConnectionService) {
        this.strategyRegistry = strategyRegistry;
        this.sshConnectionService = sshConnectionService;
    }

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
            result.setTimestamp(LocalDateTime.now());
            result.setOperation(operation.name());
            result.setServerAddress(sshConfig.getHost());
            
            return result;
        }
    }

    /**
     * 批量执行内核管理操作
     */
    public BatchCoreManagementResult executeBatchOperation(String coreType, CoreOperation operation,
                                                           List<SshConfig> sshConfigs, Object... params) {
        List<CompletableFuture<CoreManagementResult>> futures = sshConfigs.stream()
            .map(config -> CompletableFuture.supplyAsync(() -> 
                executeOperation(coreType, operation, config, params), executorService))
            .toList();

        // 等待所有操作完成
        List<CoreManagementResult> results = futures.stream()
            .map(CompletableFuture::join)
            .toList();

        // 构建批量结果
        BatchCoreManagementResult batchResult = new BatchCoreManagementResult();
        batchResult.setResults(results);
        batchResult.setTimestamp(LocalDateTime.now());
        batchResult.setOperation(operation.name());
        
        // 统计成功和失败数量
        long successCount = results.stream().mapToLong(r -> r.isSuccess() ? 1 : 0).sum();
        batchResult.setSuccessCount((int) successCount);
        batchResult.setFailureCount(results.size() - (int) successCount);
        batchResult.setTotalCount(results.size());

        return batchResult;
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
            case INSTALL -> strategy.install(connection, params);
            case UNINSTALL -> strategy.uninstall(connection);
            case UPDATE -> strategy.update(connection, params);
            case CONFIG -> strategy.config(connection, params);
        };
    }
}