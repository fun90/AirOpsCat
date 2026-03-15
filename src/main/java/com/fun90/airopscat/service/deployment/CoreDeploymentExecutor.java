package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.dto.xray.XrayConfig;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.service.core.CoreManagementService;
import com.fun90.airopscat.model.dto.deployment.CoreDeploymentExecution;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.dto.deployment.XrayNodeSnapshot;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class CoreDeploymentExecutor {

    private static final String CORE_TYPE_XRAY = "xray";
    private static final String CORE_TYPE_HYSTERIA = "hysteria";
    private static final String PROTOCOL_HYSTERIA2 = "hysteria2";
    private static final String DEFAULT_USERNAME = "root";

    private final CoreManagementService coreManagementService;
    private final ServerConfigRepository serverConfigRepository;
    private final NodeRepository nodeRepository;
    private final XrayConfigBuilder xrayConfigBuilder;

    // ── Execution ──────────────────────────────────────────────────────────────

    public List<CoreDeploymentExecution> executeForServer(DeploymentServerContext ctx) {
        Server server = ctx.server();
        log.info("Deploy nodes for server {}({}), count={}", server.getName(), server.getId(), ctx.nodes().size());

        Map<String, List<Node>> nodesByCoreType = ctx.nodes().stream()
                .collect(Collectors.groupingBy(node -> determineCoreType(node.getProtocol())));

        List<CoreDeploymentExecution> results = new ArrayList<>();
        for (Map.Entry<String, List<Node>> entry : nodesByCoreType.entrySet()) {
            results.addAll(executeForCoreType(ctx, entry.getKey(), entry.getValue()));
        }
        return results;
    }

    private List<CoreDeploymentExecution> executeForCoreType(DeploymentServerContext ctx,
                                                              String coreType, List<Node> nodes) {
        return switch (coreType) {
            case CORE_TYPE_XRAY -> List.of(executeXray(ctx, nodes));
            case CORE_TYPE_HYSTERIA -> executeHysteria(ctx.server(), nodes);
            default -> List.of(CoreDeploymentExecution.failure(ctx.server(), coreType, nodes,
                    "不支持的核心类型: " + coreType));
        };
    }

    private CoreDeploymentExecution executeXray(DeploymentServerContext ctx, List<Node> nodes) {
        Server server = ctx.server();
        List<XrayNodeSnapshot> snapshots = nodes.stream()
                .map(Node::getId)
                .map(ctx.xraySnapshotMap()::get)
                .filter(Objects::nonNull)
                .toList();

        XrayConfig xrayConfig = xrayConfigBuilder.build(ctx.serverSnapshot(), snapshots);
        String configJson = JsonUtil.toJsonStringPretty(xrayConfig);

        if (server.getExternal() == null || server.getExternal() == 0) {
            deployToServer(server, CORE_TYPE_XRAY, configJson);
        }
        return CoreDeploymentExecution.success(server, CORE_TYPE_XRAY, nodes, configJson);
    }

    private List<CoreDeploymentExecution> executeHysteria(Server server, List<Node> nodes) {
        List<CoreDeploymentExecution> results = new ArrayList<>();
        for (Node node : nodes) {
            try {
                String config = buildHysteriaConfig(node);
                deployToServer(server, CORE_TYPE_HYSTERIA, config);
                results.add(CoreDeploymentExecution.success(server, CORE_TYPE_HYSTERIA,
                        Collections.singletonList(node), config));
            } catch (Exception e) {
                log.error("Deploy hysteria node {} failed on server {}", node.getId(), server.getName(), e);
                results.add(CoreDeploymentExecution.failure(server, CORE_TYPE_HYSTERIA,
                        Collections.singletonList(node), e.getMessage()));
            }
        }
        return results;
    }

    private String buildHysteriaConfig(Node node) {
        Map<String, Object> config = node.getInbound() != null
                ? JsonUtil.toObject(node.getInbound(), Map.class)
                : new HashMap<>();
        config.put("listen", ":" + node.getPort());
        return JsonUtil.toJsonString(config);
    }

    private void deployToServer(Server server, String coreType, String config) {
        SshConfig sshConfig = buildSshConfig(server);

        CoreManagementResult configResult = coreManagementService.executeOperation(
                coreType, CoreOperation.CONFIG, sshConfig, config);
        if (configResult == null || !configResult.isSuccess()) {
            throw new RuntimeException("配置上传失败: " + (configResult != null ? configResult.getMessage() : "未知错误"));
        }

        CoreManagementResult restartResult = coreManagementService.executeOperation(
                coreType, CoreOperation.RESTART, sshConfig);
        if (restartResult == null || !restartResult.isSuccess()) {
            throw new RuntimeException("服务重启失败: " + (restartResult != null ? restartResult.getMessage() : "未知错误"));
        }
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

    // ── Persistence ────────────────────────────────────────────────────────────

    public List<DeploymentResult> persist(CoreDeploymentExecution execution) {
        if (!execution.success()) {
            return execution.nodes().stream()
                    .map(node -> toFailureResult(node, execution.message()))
                    .collect(Collectors.toList());
        }
        saveServerConfig(execution.server(), execution.coreType(), execution.config());
        return updateNodeDeploymentStatus(execution.nodes(), execution.server().getId());
    }

    private void saveServerConfig(Server server, String coreType, String config) {
        ServerConfig serverConfig = serverConfigRepository
                .findByServerIdAndConfigType(server.getId(), coreType)
                .orElseGet(() -> newServerConfig(server.getId(), coreType));
        serverConfig.setConfig(config);
        serverConfig.setUpdateTime(LocalDateTime.now());
        serverConfigRepository.persist(serverConfig);
    }

    private ServerConfig newServerConfig(Long serverId, String coreType) {
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.setServerId(serverId);
        serverConfig.setConfigType(coreType);
        serverConfig.setCreateTime(LocalDateTime.now());
        serverConfig.setPath(CORE_TYPE_XRAY.equalsIgnoreCase(coreType)
                ? "/usr/local/etc/xray/config.json"
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

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String determineCoreType(String protocol) {
        return PROTOCOL_HYSTERIA2.equalsIgnoreCase(protocol) ? CORE_TYPE_HYSTERIA : CORE_TYPE_XRAY;
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