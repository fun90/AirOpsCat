package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.deployment.CoreDeploymentExecution;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.service.NodeService;
import com.fun90.airopscat.service.core.CoreManagementService;
import com.fun90.airopscat.service.ratelimit.RateLimitService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.service.ssh.ServerSshConfigFactory;
import com.fun90.airopscat.singbox.SingBoxConfigBuilder;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class CoreDeploymentExecutor {

    private static final String CORE_TYPE_SING_BOX = "sing-box";
    private final CoreManagementService coreManagementService;
    private final ServerConfigRepository serverConfigRepository;
    private final NodeRepository nodeRepository;
    private final SingBoxConfigBuilder singBoxConfigBuilder;
    private final NodeDeploymentVersionService nodeDeploymentVersionService;
    private final NodeService nodeService;
    private final SshConnectionService sshConnectionService;
    private final ServerSshConfigFactory serverSshConfigFactory;
    private final RateLimitService rateLimitService;
    private final ExecutorService executorService;

    @Inject
    public CoreDeploymentExecutor(CoreManagementService coreManagementService,
                                  ServerConfigRepository serverConfigRepository,
                                  NodeRepository nodeRepository,
                                  SingBoxConfigBuilder singBoxConfigBuilder,
                                  NodeDeploymentVersionService nodeDeploymentVersionService,
                                  NodeService nodeService,
                                  SshConnectionService sshConnectionService,
                                  ServerSshConfigFactory serverSshConfigFactory,
                                  RateLimitService rateLimitService,
                                  @Named("deploymentTaskExecutor") ExecutorService executorService) {
        this.coreManagementService = coreManagementService;
        this.serverConfigRepository = serverConfigRepository;
        this.nodeRepository = nodeRepository;
        this.singBoxConfigBuilder = singBoxConfigBuilder;
        this.nodeDeploymentVersionService = nodeDeploymentVersionService;
        this.nodeService = nodeService;
        this.sshConnectionService = sshConnectionService;
        this.serverSshConfigFactory = serverSshConfigFactory;
        this.rateLimitService = rateLimitService;
        this.executorService = executorService;
    }

    @ConfigProperty(name = "airopscat.deployment.skip-remote-config", defaultValue = "false")
    boolean skipRemoteConfig;

    @ActivateRequestContext
    public List<CoreDeploymentExecution> executeForServer(DeploymentServerContext ctx) {
        Server server = ctx.server();
        log.info("Deploy nodes for server {}({}), count={}", server.getName(), server.getId(), ctx.nodes().size());

        List<CoreDeploymentExecution> results = new ArrayList<>();
        long startTime = System.nanoTime();
        if (!shouldDeployRemotely(server)) {
            results.add(executeConfigBuilder(ctx, ctx.nodes(), null));
            logServerDeploymentSummary(server, results, startTime);
            return results;
        }

        try (SshConnection connection = createConnection(server)) {
            results.add(executeConfigBuilder(ctx, ctx.nodes(), connection));
        } catch (Exception e) {
            log.error("Create deployment SSH connection failed for server {}", server.getName(), e);
            results.add(CoreDeploymentExecution.failure(server, CORE_TYPE_SING_BOX, ctx.nodes(), e.getMessage()));
        }
        logServerDeploymentSummary(server, results, startTime);
        return results;
    }

    private CoreDeploymentExecution executeConfigBuilder(DeploymentServerContext ctx,
                                                         List<Node> nodes,
                                                         SshConnection connection) {
        Server server = ctx.server();
        try {
            String config = buildConfig(ctx, nodes);
            if (connection != null) {
                deployToServer(connection, server, config);
            }
            return CoreDeploymentExecution.success(server, CORE_TYPE_SING_BOX, nodes, config);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return CoreDeploymentExecution.failure(server, CORE_TYPE_SING_BOX, nodes, e.getMessage());
        } catch (Exception e) {
            log.error("Deploy nodes failed for sing-box on server {}", server.getName(), e);
            return CoreDeploymentExecution.failure(server, CORE_TYPE_SING_BOX, nodes, e.getMessage());
        }
    }

    String buildConfig(DeploymentServerContext ctx, List<Node> nodes) {
        return singBoxConfigBuilder.build(ctx, nodes);
    }

    private void deployToServer(SshConnection connection, Server server, String config) {
        // 1. 上传配置
        List<CoreManagementResult> configResults = executeOperations(
                CORE_TYPE_SING_BOX,
                connection,
                server,
                new CoreManagementService.OperationRequest(CoreOperation.CONFIG, config)
        );
        CoreManagementResult configResult = configResults.getFirst();
        if (configResult == null || !configResult.isSuccess()) {
            throw new RuntimeException("配置上传失败: " + (configResult != null ? configResult.getMessage() : "未知错误"));
        }

        // 2. 重启服务使主配置生效
        List<CoreManagementResult> restartResults = executeOperations(
                CORE_TYPE_SING_BOX,
                connection,
                server,
                new CoreManagementService.OperationRequest(CoreOperation.RESTART)
        );
        CoreManagementResult restartResult = restartResults.getFirst();
        if (restartResult == null || !restartResult.isSuccess()) {
            throw new RuntimeException("配置上传成功，重启失败: "
                    + (restartResult != null ? restartResult.getMessage() : "未知错误"));
        }
        log.info("配置重启生效成功: server={}({})", server.getName(), server.getId());

        CompletableFuture.runAsync(() -> {
            try {
                if (rateLimitService.isEnabled()) {
                    rateLimitService.syncServer(server);
                }
            } catch (Exception e) {
                log.warn("同步服务器 {} 限速配置失败，可在账号变更或重新部署后自动恢复", server.getId(), e);
            }
        }, executorService);
    }

    SshConnection createConnection(Server server) {
        return sshConnectionService.createConnection(serverSshConfigFactory.create(server));
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
                                            List<CoreDeploymentExecution> results,
                                            long startTime) {
        long successCount = results.stream().filter(CoreDeploymentExecution::success).count();
        long failureCount = results.size() - successCount;
        long elapsedMillis = (System.nanoTime() - startTime) / 1_000_000;
        log.info("节点部署服务器批次完成: server={}({}), core=sing-box, success={}, failure={}, elapsedMs={}",
                server.getName(), server.getId(), successCount, failureCount, elapsedMillis);
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
                        .filter(node -> !NodeDeploymentStatus.isPendingDelete(node.getDeployed()))
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
            boolean shouldEnable = CORE_TYPE_SING_BOX.equalsIgnoreCase(serverConfig.getConfigType())
                    && hasActiveNodeUsage(serverId);
            serverConfig.setEnabled(shouldEnable ? 1 : 0);
        }
    }

    private boolean hasActiveNodeUsage(Long serverId) {
        return nodeRepository.countActiveByServerAssociation(serverId) > 0;
    }

    private ServerConfig newServerConfig(Long serverId, String coreType) {
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.setServerId(serverId);
        serverConfig.setConfigType(coreType);
        serverConfig.setCreateTime(LocalDateTime.now());
        serverConfig.setEnabled(1);
        serverConfig.setPath("/etc/sing-box/config.json");
        return serverConfig;
    }

    private List<DeploymentResult> updateNodeDeploymentStatus(List<Node> nodes, Long serverId) {
        List<DeploymentResult> results = new ArrayList<>();
        for (Node node : nodes) {
            try {
                if (serverId.equals(node.getServerId())) {
                    if (NodeDeploymentStatus.isPendingDelete(node.getDeployed())) {
                        nodeService.physicallyDeleteNode(node.getId());
                    } else {
                        node.setDeployed(NodeDeploymentStatus.DEPLOYED.getValue());
                        nodeRepository.persist(node);
                    }
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
