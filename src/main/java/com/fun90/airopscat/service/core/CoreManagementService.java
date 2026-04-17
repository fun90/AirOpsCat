package com.fun90.airopscat.service.core;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.singbox.SingBoxCoreManager;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 内核管理服务
 */
@Slf4j
@ApplicationScoped
public class CoreManagementService {

    @Inject
    SingBoxCoreManager singBoxCoreManager;

    @Inject
    SshConnectionService sshConnectionService;

    /**
     * 在同一个SSH连接中顺序执行多个内核管理操作
     */
    public List<CoreManagementResult> executeOperations(String coreType,
                                                        SshConfig sshConfig,
                                                        OperationRequest... requests) {
        try {
            try (SshConnection connection = sshConnectionService.createConnection(sshConfig)) {
                return executeOperations(coreType, connection, sshConfig.getHost(), requests);
            }

        } catch (Exception e) {
            return buildExecutionFailureResults(coreType, sshConfig.getHost(), requests, e);
        }
    }

    public List<CoreManagementResult> executeOperations(String coreType,
                                                        SshConnection connection,
                                                        String serverAddress,
                                                        OperationRequest... requests) {
        String normalizedCoreType = normalizeCoreType(coreType);
        try {
            List<CoreManagementResult> results = new ArrayList<>(requests.length);
            for (OperationRequest request : requests) {
                if (!results.isEmpty() && !results.getLast().isSuccess()) {
                    results.add(buildFailureResult(normalizedCoreType, request.operation(), serverAddress,
                            "操作未执行: 前序操作失败"));
                    continue;
                }
                CoreManagementResult result = executeOperationInternal(
                        singBoxCoreManager, request.operation(), connection, request.params());
                if (result == null) {
                    result = buildFailureResult(normalizedCoreType, request.operation(), serverAddress, "暂不支持该操作");
                } else {
                    enrichResult(result, normalizedCoreType, request.operation(), serverAddress);
                }
                results.add(result);
            }
            return results;
        } catch (Exception e) {
            return buildExecutionFailureResults(normalizedCoreType, serverAddress, requests, e);
        }
    }

    private String normalizeCoreType(String coreType) {
        if (coreType == null || coreType.trim().isEmpty() || "sing-box".equalsIgnoreCase(coreType.trim())) {
            return "sing-box";
        }
        throw new IllegalArgumentException("当前仅支持 sing-box 内核");
    }

    private CoreManagementResult executeOperationInternal(SingBoxCoreManager coreManager,
                                                         CoreOperation operation,
                                                         SshConnection connection,
                                                         Object... params) {
        return switch (operation) {
            case START -> coreManager.start(connection);
            case STOP -> coreManager.stop(connection);
            case RESTART -> coreManager.restart(connection);
            case RELOAD -> coreManager.reload(connection);
            case STATUS -> coreManager.status(connection);
            case VALIDATE_CONFIG -> null;
            case INSTALL -> coreManager.install(connection, params);
            case UNINSTALL -> coreManager.uninstall(connection);
            case UPDATE -> coreManager.update(connection, params);
            case CONFIG -> coreManager.config(connection, params);
            case GET_VERSION -> null;
            case GET_LOGS -> null;
            case IS_INSTALLED -> null;
        };
    }

    private void enrichResult(CoreManagementResult result,
                              String coreType,
                              CoreOperation operation,
                              String serverAddress) {
        if (result.getOperation() == null) {
            result.setOperation(operation.name());
        }
        if (result.getCoreType() == null) {
            result.setCoreType(coreType);
        }
        result.setServerAddress(serverAddress);
        if (result.getOperationTime() == null) {
            result.setOperationTime(LocalDateTime.now());
        }
    }

    private CoreManagementResult buildFailureResult(String coreType,
                                                    CoreOperation operation,
                                                    String serverAddress,
                                                    String message) {
        CoreManagementResult result = new CoreManagementResult();
        result.setSuccess(false);
        result.setMessage(message);
        result.setOperationTime(LocalDateTime.now());
        result.setOperation(operation.name());
        result.setCoreType(coreType);
        result.setServerAddress(serverAddress);
        return result;
    }

    private List<CoreManagementResult> buildExecutionFailureResults(String coreType,
                                                                    String serverAddress,
                                                                    OperationRequest[] requests,
                                                                    Exception e) {
        String operations = List.of(requests).stream()
                .map(request -> request.operation().name())
                .reduce((left, right) -> left + "," + right)
                .orElse("UNKNOWN");
        log.error("执行内核操作失败 [{}:{}]: {}", coreType, operations, e.getMessage());

        List<CoreManagementResult> results = new ArrayList<>(requests.length);
        for (OperationRequest request : requests) {
            results.add(buildFailureResult(coreType, request.operation(), serverAddress,
                    "操作执行失败: " + e.getMessage()));
        }
        return results;
    }

    public record OperationRequest(CoreOperation operation, Object... params) {
    }
}
