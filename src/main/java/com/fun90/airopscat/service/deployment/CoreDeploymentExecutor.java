package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.deployment.CoreDeploymentExecution;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.repository.NodeServerRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class CoreDeploymentExecutor {

    private static final String CORE_TYPE_SING_BOX = "sing-box";
    private final CoreManagementService coreManagementService;
    private final NodeServerRepository nodeServerRepository;
    private final SingBoxConfigBuilder singBoxConfigBuilder;
    private final NodeDeploymentVersionService nodeDeploymentVersionService;
    private final NodeService nodeService;
    private final SshConnectionService sshConnectionService;
    private final ServerSshConfigFactory serverSshConfigFactory;
    private final RateLimitService rateLimitService;
    private final ExecutorService executorService;

    @Inject
    public CoreDeploymentExecutor(CoreManagementService coreManagementService,
                                  NodeServerRepository nodeServerRepository,
                                  SingBoxConfigBuilder singBoxConfigBuilder,
                                  NodeDeploymentVersionService nodeDeploymentVersionService,
                                  NodeService nodeService,
                                  SshConnectionService sshConnectionService,
                                  ServerSshConfigFactory serverSshConfigFactory,
                                  RateLimitService rateLimitService,
                                  @Named("deploymentTaskExecutor") ExecutorService executorService) {
        this.coreManagementService = coreManagementService;
        this.nodeServerRepository = nodeServerRepository;
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
        log.info("Deploy nodes for server {}({}), count={}", server.getName(), server.getId(), ctx.placements().size());

        List<CoreDeploymentExecution> results = new ArrayList<>();
        long startTime = System.nanoTime();
        if (!shouldDeployRemotely(server)) {
            results.add(executeConfigBuilder(ctx, ctx.placements(), null));
            logServerDeploymentSummary(server, results, startTime);
            return results;
        }

        try (SshConnection connection = createConnection(server)) {
            results.add(executeConfigBuilder(ctx, ctx.placements(), connection));
        } catch (Exception e) {
            log.error("Create deployment SSH connection failed for server {}", server.getName(), e);
            results.add(CoreDeploymentExecution.failure(server, CORE_TYPE_SING_BOX, ctx.placements(), e.getMessage()));
        }
        logServerDeploymentSummary(server, results, startTime);
        return results;
    }

    private CoreDeploymentExecution executeConfigBuilder(DeploymentServerContext ctx,
                                                         List<NodeServer> nodes,
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

    String buildConfig(DeploymentServerContext ctx, List<NodeServer> nodes) {
        if (rateLimitService.isEnabled()) {
            return singBoxConfigBuilder.build(ctx, nodes, rateLimitService.buildOverrides(ctx));
        }
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
            return execution.placements().stream()
                    .map(placement -> toResult(placement, false, execution.message()))
                    .collect(Collectors.toList());
        }
        List<DeploymentResult> results = new ArrayList<>();
        List<NodeServer> deployedPlacements = new ArrayList<>();
        Long serverId = execution.server().getId();
        for (NodeServer placement : execution.placements()) {
            try {
                if (serverId.equals(placement.getServerId())) {
                    if (NodeDeploymentStatus.isPendingDelete(placement.getDeployed())) {
                        nodeService.physicallyDeletePlacement(placement.getId());
                    } else {
                        placement.setDeployed(NodeDeploymentStatus.DEPLOYED.getValue());
                        nodeServerRepository.persist(placement);
                        deployedPlacements.add(placement);
                    }
                }
                results.add(toResult(placement, true, "节点部署成功"));
            } catch (Exception e) {
                log.error("Update node placement {} deployment status failed", placement.getId(), e);
                results.add(toResult(placement, false, "状态更新失败: " + e.getMessage()));
            }
        }
        nodeDeploymentVersionService.recordSuccessfulDeployments(deployedPlacements);
        return results;
    }

    private DeploymentResult toResult(NodeServer placement, boolean success, String message) {
        DeploymentResult result = new DeploymentResult();
        result.setNodeId(placement.getNodeId());
        result.setServerId(placement.getServerId());
        result.setSuccess(success);
        result.setMessage(message);
        return result;
    }
}
