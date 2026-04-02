package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.dto.deployment.CoreDeploymentExecution;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.service.core.CoreManagementService;
import com.fun90.airopscat.service.deployment.registry.CoreConfigBuilderRegistry;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class CoreDeploymentExecutor {

    private static final String CORE_TYPE_XRAY = "xray";
    private static final String CORE_TYPE_SING_BOX = "sing-box";
    private static final String DEFAULT_USERNAME = "root";

    private final CoreManagementService coreManagementService;
    private final ServerConfigRepository serverConfigRepository;
    private final NodeRepository nodeRepository;
    private final CoreConfigBuilderRegistry coreConfigBuilderRegistry;
    private final NodeDeploymentVersionService nodeDeploymentVersionService;
    private final SshConnectionService sshConnectionService;

    @ConfigProperty(name = "airopscat.deployment.skip-remote-config", defaultValue = "false")
    boolean skipRemoteConfig;

    public List<CoreDeploymentExecution> executeForServer(DeploymentServerContext ctx) {
        Server server = ctx.server();
        log.info("Deploy nodes for server {}({}), count={}", server.getName(), server.getId(), ctx.nodes().size());

        Map<String, List<Node>> nodesByCoreType = ctx.nodes().stream()
                .collect(Collectors.groupingBy(node -> node.getCoreType().trim().toLowerCase()));

        List<CoreDeploymentExecution> results = new ArrayList<>();
        long startTime = System.nanoTime();
        if (!shouldDeployRemotely(server)) {
            for (Map.Entry<String, List<Node>> entry : nodesByCoreType.entrySet()) {
                results.add(executeConfigBuilder(ctx, entry.getKey(), entry.getValue(), null));
            }
            logServerDeploymentSummary(server, nodesByCoreType.size(), results, startTime);
            return results;
        }

        try (SshConnection connection = createConnection(server)) {
            for (Map.Entry<String, List<Node>> entry : nodesByCoreType.entrySet()) {
                results.add(executeConfigBuilder(ctx, entry.getKey(), entry.getValue(), connection));
            }
        } catch (Exception e) {
            log.error("Create deployment SSH connection failed for server {}", server.getName(), e);
            for (Map.Entry<String, List<Node>> entry : nodesByCoreType.entrySet()) {
                results.add(CoreDeploymentExecution.failure(server, entry.getKey(), entry.getValue(), e.getMessage()));
            }
        }
        logServerDeploymentSummary(server, nodesByCoreType.size(), results, startTime);
        return results;
    }

    private CoreDeploymentExecution executeConfigBuilder(DeploymentServerContext ctx,
                                                         String coreType,
                                                         List<Node> nodes,
                                                         SshConnection connection) {
        Server server = ctx.server();
        try {
            String config = buildConfig(ctx, coreType, nodes);
            if (connection != null) {
                deployToServer(connection, server, coreType, config);
            }
            return CoreDeploymentExecution.success(server, coreType, nodes, config);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return CoreDeploymentExecution.failure(server, coreType, nodes, e.getMessage());
        } catch (Exception e) {
            log.error("Deploy nodes failed for core {} on server {}", coreType, server.getName(), e);
            return CoreDeploymentExecution.failure(server, coreType, nodes, e.getMessage());
        }
    }

    String buildConfig(DeploymentServerContext ctx, String coreType, List<Node> nodes) {
        return coreConfigBuilderRegistry.getStrategy(coreType).build(ctx, nodes);
    }

    private void deployToServer(SshConnection connection, Server server, String coreType, String config) {
        List<CoreManagementResult> results = executeOperations(
                coreType,
                connection,
                server,
                new CoreManagementService.OperationRequest(CoreOperation.CONFIG, config),
                new CoreManagementService.OperationRequest(CoreOperation.RESTART)
        );
        CoreManagementResult configResult = results.getFirst();
        if (configResult == null || !configResult.isSuccess()) {
            throw new RuntimeException("配置上传失败: " + (configResult != null ? configResult.getMessage() : "未知错误"));
        }

        CoreManagementResult restartResult = results.get(1);
        if (restartResult == null || !restartResult.isSuccess()) {
            throw new RuntimeException("服务重启失败: " + (restartResult != null ? restartResult.getMessage() : "未知错误"));
        }
    }

    SshConnection createConnection(Server server) {
        return sshConnectionService.createConnection(buildSshConfig(server));
    }

    List<CoreManagementResult> executeOperations(String coreType,
                                                 SshConnection connection,
                                                 Server server,
                                                 CoreManagementService.OperationRequest... requests) {
        return coreManagementService.executeOperations(coreType, connection, server.getIp(), requests);
    }

    boolean shouldDeployRemotely(Server server) {
        if (server.getExternal() != null && server.getExternal() == 1) {
            return false;
        }
        if (skipRemoteConfig) {
            log.info("Skip remote config deployment in current environment, server={}({})",
                    server.getName(), server.getId());
            return false;
        }
        return true;
    }

    private void logServerDeploymentSummary(Server server,
                                            int coreCount,
                                            List<CoreDeploymentExecution> results,
                                            long startTime) {
        long successCount = results.stream().filter(CoreDeploymentExecution::success).count();
        long failureCount = results.size() - successCount;
        long elapsedMillis = (System.nanoTime() - startTime) / 1_000_000;
        log.info("节点部署服务器批次完成: server={}({}), coreCount={}, success={}, failure={}, elapsedMs={}",
                server.getName(), server.getId(), coreCount, successCount, failureCount, elapsedMillis);
    }

    private SshConfig buildSshConfig(Server server) {
        SshConfig sshConfig = new SshConfig();
        sshConfig.setHost(server.getIp());
        sshConfig.setPort(server.getSshPort());
        sshConfig.setUsername(Objects.toString(server.getUsername(), DEFAULT_USERNAME));

        boolean isPassword = "PASSWORD".equalsIgnoreCase(server.getAuthType())
                || "password".equalsIgnoreCase(server.getAuthType());
        if (isPassword) {
            sshConfig.setPassword(server.getAuth());
        } else {
            sshConfig.setPrivateKeyContent(server.getAuth());
        }
        return sshConfig;
    }

    public List<DeploymentResult> persist(CoreDeploymentExecution execution) {
        if (!execution.success()) {
            return execution.nodes().stream()
                    .map(node -> toFailureResult(node, execution.message()))
                    .collect(Collectors.toList());
        }
        saveServerConfig(execution.server(), execution.coreType(), execution.config());
        List<DeploymentResult> results = updateNodeDeploymentStatus(execution.nodes(), execution.server().getId());
        nodeDeploymentVersionService.recordSuccessfulDeployments(
                results.stream()
                        .filter(DeploymentResult::isSuccess)
                        .map(result -> nodeRepository.findById(result.getNodeId()))
                        .filter(Objects::nonNull)
                        .toList()
        );
        return results;
    }

    private void saveServerConfig(Server server, String coreType, String config) {
        ServerConfig serverConfig = serverConfigRepository
                .findByServerIdAndConfigType(server.getId(), coreType)
                .orElseGet(() -> newServerConfig(server.getId(), coreType));
        serverConfig.setConfig(config);
        serverConfig.setEnabled(1);
        serverConfig.setUpdateTime(LocalDateTime.now());
        serverConfigRepository.persist(serverConfig);
        reconcileServerConfigStatuses(server.getId());
    }

    private void reconcileServerConfigStatuses(Long serverId) {
        for (ServerConfig serverConfig : serverConfigRepository.findByServerId(serverId)) {
            boolean shouldEnable = hasActiveCoreUsage(serverId, serverConfig.getConfigType());
            serverConfig.setEnabled(shouldEnable ? 1 : 0);
        }
    }

    private boolean hasActiveCoreUsage(Long serverId, String coreType) {
        return nodeRepository.countActiveByServerAssociationAndCoreType(serverId, coreType) > 0;
    }

    private ServerConfig newServerConfig(Long serverId, String coreType) {
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.setServerId(serverId);
        serverConfig.setConfigType(coreType);
        serverConfig.setCreateTime(LocalDateTime.now());
        serverConfig.setEnabled(1);
        serverConfig.setPath(CORE_TYPE_XRAY.equalsIgnoreCase(coreType)
                ? "/usr/local/etc/xray/config.json"
                : CORE_TYPE_SING_BOX.equalsIgnoreCase(coreType)
                ? "/etc/sing-box/config.json"
                : "/etc/hysteria/config.json");
        return serverConfig;
    }

    private List<DeploymentResult> updateNodeDeploymentStatus(List<Node> nodes, Long serverId) {
        List<DeploymentResult> results = new ArrayList<>();
        for (Node node : nodes) {
            try {
                if (serverId.equals(node.getServerId())) {
                    node.setDeployed(1);
                    nodeRepository.persist(node);
                }
                results.add(toSuccessResult(node, "节点部署成功"));
            } catch (Exception e) {
                log.error("Update node {} deployment status failed", node.getId(), e);
                results.add(toFailureResult(node, "状态更新失败: " + e.getMessage()));
            }
        }
        return results;
    }

    private DeploymentResult toSuccessResult(Node node, String message) {
        DeploymentResult result = new DeploymentResult();
        result.setNodeId(node.getId());
        result.setServerId(node.getServerId());
        result.setSuccess(true);
        result.setMessage(message);
        return result;
    }

    private DeploymentResult toFailureResult(Node node, String message) {
        DeploymentResult result = new DeploymentResult();
        result.setNodeId(node.getId());
        result.setServerId(node.getServerId());
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }
}
