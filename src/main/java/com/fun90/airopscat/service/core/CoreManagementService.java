package com.fun90.airopscat.service.core;

import com.fun90.airopscat.model.dto.BatchCoreManagementResult;
import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.service.SshService;
import com.fun90.airopscat.service.core.registry.CoreManagementStrategyRegistry;
import com.fun90.airopscat.service.core.strategy.CoreManagementStrategy;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内核管理服务
 */
@Slf4j
@Service
public class CoreManagementService {

    private final CoreManagementStrategyRegistry strategyRegistry;
    private final SshService sshService;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @Autowired
    public CoreManagementService(CoreManagementStrategyRegistry strategyRegistry, SshService sshService) {
        this.strategyRegistry = strategyRegistry;
        this.sshService = sshService;
    }

    /**
     * 执行内核管理操作
     */
    public CoreManagementResult executeOperation(String coreType, CoreOperation operation,
                                                 SshConfig sshConfig, Object... params) {
        try {
            CoreManagementStrategy strategy = strategyRegistry.getStrategy(coreType);
            Session session = sshService.connectToServer(sshConfig);

            CoreManagementResult result = executeOperationInternal(strategy, operation, session, params);
            result.setServerAddress(sshConfig.getHost());

            session.disconnect();
            return result;
        } catch (Exception e) {
            log.error("执行内核操作失败 [{}:{}]: {}", coreType, operation.getCode(), e.getMessage(), e);
            return CoreManagementResult.failure(operation.getCode(), coreType,
                    "操作执行失败", e.getMessage());
        }
    }

    /**
     * 批量执行内核管理操作
     */
    public CompletableFuture<BatchCoreManagementResult> batchExecuteOperation(
            String coreType, CoreOperation operation, List<SshConfig> sshConfigs, Object... params) {

        BatchCoreManagementResult batchResult = new BatchCoreManagementResult();
        batchResult.setOperation(operation.getCode());
        batchResult.setCoreType(coreType);
        batchResult.setStartTime(LocalDateTime.now());

        return CompletableFuture.supplyAsync(() -> {
            CoreManagementStrategy strategy;
            try {
                strategy = strategyRegistry.getStrategy(coreType);
            } catch (Exception e) {
                log.error("获取内核策略失败: {}", e.getMessage(), e);
                batchResult.setEndTime(LocalDateTime.now());
                return batchResult;
            }

            for (SshConfig sshConfig : sshConfigs) {
                try {
                    Session session = sshService.connectToServer(sshConfig);
                    CoreManagementResult result = executeOperationInternal(strategy, operation, session, params);
                    result.setServerAddress(sshConfig.getHost());

                    batchResult.addResult(sshConfig.getHost(), result);
                    session.disconnect();

                } catch (Exception e) {
                    log.error("批量执行内核操作失败 [{}]: {}", sshConfig.getHost(), e.getMessage(), e);
                    CoreManagementResult errorResult = CoreManagementResult.failure(
                            operation.getCode(), coreType, "连接或执行错误", e.getMessage());
                    errorResult.setServerAddress(sshConfig.getHost());
                    batchResult.addResult(sshConfig.getHost(), errorResult);
                }
            }

            batchResult.setEndTime(LocalDateTime.now());
            return batchResult;
        }, executorService);
    }

    /**
     * 重启内核服务（带配置验证）
     */
    public CoreManagementResult restartWithValidation(String coreType, SshConfig sshConfig, String configPath) {
        try {
            CoreManagementStrategy strategy = strategyRegistry.getStrategy(coreType);
            Session session = sshService.connectToServer(sshConfig);

            // 先验证配置
            CoreManagementResult validationResult = strategy.validateConfig(session, configPath);
            if (!validationResult.isSuccess()) {
                session.disconnect();
                validationResult.setMessage("配置验证失败，取消重启操作");
                return validationResult;
            }

            // 配置验证通过，执行重启
            CoreManagementResult restartResult = strategy.restart(session);
            restartResult.setServerAddress(sshConfig.getHost());

            session.disconnect();
            return restartResult;
        } catch (Exception e) {
            log.error("带验证的重启操作失败 [{}]: {}", coreType, e.getMessage(), e);
            return CoreManagementResult.failure("restart", coreType, "重启失败", e.getMessage());
        }
    }

    /**
     * 更新配置并重启服务
     */
    public CoreManagementResult updateConfigAndRestart(String coreType, SshConfig sshConfig,
                                                       String configContent, String configPath) {
        try {
            CoreManagementStrategy strategy = strategyRegistry.getStrategy(coreType);
            Session session = sshService.connectToServer(sshConfig);

            // 更新配置
            CoreManagementResult updateResult = strategy.updateConfig(session, configContent, configPath);
            if (!updateResult.isSuccess()) {
                session.disconnect();
                return updateResult;
            }

            // 重启服务
            CoreManagementResult restartResult = strategy.restart(session);
            restartResult.setServerAddress(sshConfig.getHost());

            // 合并结果信息
            restartResult.setMessage(updateResult.getMessage() + "；" + restartResult.getMessage());

            session.disconnect();
            return restartResult;
        } catch (Exception e) {
            log.error("更新配置并重启失败 [{}]: {}", coreType, e.getMessage(), e);
            return CoreManagementResult.failure("update_and_restart", coreType,
                    "更新配置并重启失败", e.getMessage());
        }
    }

    /**
     * 检查内核健康状态
     */
    public CoreManagementResult healthCheck(String coreType, SshConfig sshConfig) {
        try {
            CoreManagementStrategy strategy = strategyRegistry.getStrategy(coreType);
            Session session = sshService.connectToServer(sshConfig);

            // 检查安装状态
            CoreManagementResult installCheck = strategy.isInstalled(session);
            if (!installCheck.getOutput().contains("已安装")) {
                session.disconnect();
                return CoreManagementResult.failure("health_check", coreType,
                        "健康检查失败", "内核未安装");
            }

            // 检查服务状态
            CoreManagementResult statusCheck = strategy.status(session);

            // 获取版本信息
            CoreManagementResult versionCheck = strategy.getVersion(session);

            // 合并健康检查结果
            CoreManagementResult healthResult = new CoreManagementResult();
            healthResult.setOperation("health_check");
            healthResult.setCoreType(coreType);
            healthResult.setServerAddress(sshConfig.getHost());
            healthResult.setSuccess(statusCheck.isSuccess() &&
                    statusCheck.getOutput().contains("active (running)"));

            StringBuilder output = new StringBuilder();
            output.append("=== 内核健康检查报告 ===\n");
            output.append("安装状态: ").append(installCheck.getOutput()).append("\n");
            output.append("服务状态: ").append(statusCheck.getOutput()).append("\n");
            output.append("版本信息: ").append(versionCheck.getOutput()).append("\n");

            healthResult.setOutput(output.toString());
            healthResult.setMessage(healthResult.isSuccess() ? "健康检查通过" : "健康检查失败");

            session.disconnect();
            return healthResult;
        } catch (Exception e) {
            log.error("健康检查失败 [{}]: {}", coreType, e.getMessage(), e);
            return CoreManagementResult.failure("health_check", coreType,
                    "健康检查失败", e.getMessage());
        }
    }

    /**
     * 获取支持的内核类型
     */
    public List<String> getSupportedCoreTypes() {
        return strategyRegistry.getRegisteredCores().stream().sorted().toList();
    }

    /**
     * 获取策略信息
     */
    public Map<String, String> getStrategyInfo() {
        return strategyRegistry.getStrategyInfo();
    }

    /**
     * 根据操作系统获取支持的内核类型
     */
    public List<String> getSupportedCoreTypesByOS(String osType) {
        return strategyRegistry.getSupportedCoresByOS(osType).stream().sorted().toList();
    }

    /**
     * 内部执行操作的方法
     */
    private CoreManagementResult executeOperationInternal(CoreManagementStrategy strategy,
                                                          CoreOperation operation, Session session, Object... params) {
        switch (operation) {
            case START:
                return strategy.start(session);
            case STOP:
                return strategy.stop(session);
            case RESTART:
                return strategy.restart(session);
            case RELOAD:
                return strategy.reload(session);
            case STATUS:
                return strategy.status(session);
            case VALIDATE_CONFIG:
                String configPath = params.length > 0 ? (String) params[0] : null;
                return strategy.validateConfig(session, configPath);
            case INSTALL:
                String version = params.length > 0 ? (String) params[0] : null;
                return strategy.install(session, version);
            case UNINSTALL:
                return strategy.uninstall(session);
            case UPDATE_CONFIG:
                if (params.length < 2) {
                    return CoreManagementResult.failure(operation.getCode(),
                            strategy.getStrategyName(), "参数不足", "需要配置内容和路径参数");
                }
                return strategy.updateConfig(session, (String) params[0], (String) params[1]);
            case GET_VERSION:
                return strategy.getVersion(session);
            case GET_LOGS:
                int lines = params.length > 0 ? (Integer) params[0] : 50;
                return strategy.getLogs(session, lines);
            case IS_INSTALLED:
                return strategy.isInstalled(session);
            default:
                return CoreManagementResult.failure(operation.getCode(),
                        strategy.getStrategyName(), "不支持的操作", "未知操作类型: " + operation);
        }
    }
}