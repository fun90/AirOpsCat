package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.OutboundConfig;
import com.fun90.airopscat.model.dto.xray.RoutingConfig;
import com.fun90.airopscat.model.dto.xray.XrayConfig;
import com.fun90.airopscat.model.dto.xray.routing.RoutingRule;
import com.fun90.airopscat.model.dto.xray.setting.InboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.VlessInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.VlessInboundSetting.VlessClient;
import com.fun90.airopscat.model.entity.*;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.repository.*;
import com.fun90.airopscat.service.core.CoreManagementService;
import com.fun90.airopscat.service.xray.registry.ConversionStrategyRegistry;
import com.fun90.airopscat.service.xray.strategy.ConversionStrategy;
import com.fun90.airopscat.util.ConfigFileReader;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 节点部署服务 - 负责节点的部署和配置管理
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class NodeDeploymentService {

    private final NodeRepository nodeRepository;
    private final ServerRepository serverRepository;
    private final ServerConfigRepository serverConfigRepository;
    private final ConversionStrategyRegistry strategyRegistry;
    private final CoreManagementService coreManagementService;
    private final TagRepository tagRepository;
    private final ConfigFileReader configFileReader;
    private final AccountTrafficStatsRepository accountTrafficRepository;

    private static final String CORE_TYPE_HYSTERIA = "hysteria";
    private static final String CORE_TYPE_XRAY = "xray";
    private static final String PROTOCOL_HYSTERIA2 = "hysteria2";
    private static final String DEFAULT_USERNAME = "root";

    @Transactional
    public List<DeploymentResult> deployByAccountIds(List<Long> accountIdList) {
        List<Node> nodes = tagRepository.findNodesByAccountIds(accountIdList);
        if (nodes.isEmpty()) {
            log.info("账户没有关联的节点");
            return Collections.emptyList();
        }
        log.info("将账户发布到节点，节点数量: {}", nodes.size());

       return deployNodesForcibly(nodes);
    }

    /**
     * 批量部署节点
     *
     * @param nodeIds 节点ID列表，如果为空则部署所有未部署的节点
     * @return 部署结果列表
     */
    @Transactional
    public List<DeploymentResult> deployNodes(List<Long> nodeIds) {
        log.info("开始批量部署节点，节点ID列表: {}", nodeIds);

        try {
            List<Node> undeployedNodes = getUndeployedNodes(nodeIds);
            if (undeployedNodes.isEmpty()) {
                log.info("没有找到需要部署的节点");
                return Collections.emptyList();
            }

            return processNodesByServer(undeployedNodes);
        } catch (Exception e) {
            log.error("批量部署节点失败", e);
            throw new RuntimeException("节点部署失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public List<DeploymentResult> deployNodesForcibly(List<Node> nodes) {
        log.info("开始批量部署节点，节点列表: {}", nodes);

        try {
            return processNodesByServer(nodes);
        } catch (Exception e) {
            log.error("批量部署节点失败", e);
            throw new RuntimeException("节点部署失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取未部署的节点列表
     */
    private List<Node> getUndeployedNodes(List<Long> nodeIds) {
        if (nodeIds != null && !nodeIds.isEmpty()) {
            return nodeRepository.findByDeployedAndIdIn(0, nodeIds);
        } else {
            return nodeRepository.findByDeployed(0);
        }
    }

    /**
     * 按服务器分组处理节点
     */
    private List<DeploymentResult> processNodesByServer(List<Node> nodes) {
        List<DeploymentResult> results = new ArrayList<>();

        // 收集所有需要部署的服务器ID（包括主服务器和备用服务器）
        Set<Long> serverIds = new HashSet<>();
        nodes.forEach(node -> {
            serverIds.add(node.getServerId());
            if (node.getBackupServerId() != null) {
                serverIds.add(node.getBackupServerId());
            }
        });

        for (Long serverId : serverIds) {
            // 一次性查询该服务器的所有相关节点（作为主服务器或备用服务器）
            List<Node> allServerNodes = nodeRepository.findByServerIdOrBackupServerId(serverId);

            if (!allServerNodes.isEmpty()) {
                Server server = serverRepository.findById(serverId);
                if (server == null) {
                    throw new IllegalArgumentException("服务器不存在，serverId: " + serverId);
                }
                results.addAll(deployNodesForServer(server, allServerNodes));
            }
        }

        return results;
    }

    /**
     * 为特定服务器部署节点
     */
    private List<DeploymentResult> deployNodesForServer(Server server, List<Node> nodes) {
        log.info("开始为服务器[{}](ID: {}) 部署 {} 个节点", server.getName(), server.getId(), nodes.size());

        Map<String, List<Node>> coreTypeNodeMap = nodes.stream()
                .collect(Collectors.groupingBy(node -> determineCoreType(node.getProtocol())));

        List<DeploymentResult> results = new ArrayList<>();

        for (Map.Entry<String, List<Node>> coreEntry : coreTypeNodeMap.entrySet()) {
            String coreType = coreEntry.getKey();
            List<Node> coreNodes = coreEntry.getValue();
            results.addAll(deployNodesForCore(server, coreType, coreNodes));
        }

        return results;
    }

    /**
     * 为特定核心类型部署节点
     */
    private List<DeploymentResult> deployNodesForCore(Server server, String coreType, List<Node> nodes) {
        log.info("为服务器 {} 的 {} 核心部署 {} 个节点", server.getName(), coreType, nodes.size());

        List<DeploymentResult> results = new ArrayList<>();

        if (CORE_TYPE_XRAY.equals(coreType)) {
            results.addAll(deployXrayNodes(server, nodes));
        } else if (CORE_TYPE_HYSTERIA.equals(coreType)) {
            results.addAll(deployHysteriaNodes(server, nodes));
        } else {
            log.warn("不支持的核心类型: {}", coreType);
            results.addAll(createFailureResults(nodes, "不支持的核心类型: " + coreType));
        }

        return results;
    }

    /**
     * 部署Xray节点
     */
    private List<DeploymentResult> deployXrayNodes(Server server, List<Node> nodes) {
        log.info("为服务器 {} 部署 {} 个 Xray 节点", server.getName(), nodes.size());

        // 1. 生成Xray配置
        XrayConfig xrayConfig = generateXrayConfig(server, nodes);

        // 2. 保存服务器配置
        ServerConfig serverConfig = saveServerConfig(server, CORE_TYPE_XRAY, xrayConfig);

        // 3. 远程部署配置：托管给外部的服务器不需要部署配置
        if (server.getExternal() == null || server.getExternal() == 0) {
            deployConfigToServer(server, CORE_TYPE_XRAY, serverConfig.getConfig());
        }

        // 4. 更新节点状态（只更新以该服务器为主服务器的节点状态）
        return new ArrayList<>(updateNodeDeploymentStatus(nodes, server.getId()));
    }

    /**
     * 部署Hysteria节点
     */
    private List<DeploymentResult> deployHysteriaNodes(Server server, List<Node> nodes) {
        log.info("为服务器 {} 部署 {} 个 Hysteria 节点", server.getName(), nodes.size());
        
        List<DeploymentResult> results = new ArrayList<>();

        for (Node node : nodes) {
            try {
                // Hysteria节点单独部署
                String hysteriaConfig = generateHysteriaConfig(node);
                ServerConfig serverConfig = saveServerConfig(server, CORE_TYPE_HYSTERIA, hysteriaConfig);
                deployConfigToServer(server, CORE_TYPE_HYSTERIA, serverConfig.getConfig());

                results.add(updateSingleNodeDeploymentStatus(node, server.getId()));
            } catch (Exception e) {
                log.error("服务器 {} 的 Hysteria节点 {} 部署失败", server.getName(), node.getId(), e);
                results.add(createFailureResult(node, e.getMessage()));
            }
        }

        return results;
    }

    /**
     * 生成Xray配置
     */
    private XrayConfig generateXrayConfig(Server server, List<Node> nodes) {
        List<Node> enabledNodes = nodes.stream().filter(o -> o.getDisabled() == 0).toList();
        String configTemplate = configFileReader.readFileContent("templates/core/xray.json");
        XrayConfig xrayConfig = JsonUtil.toObject(configTemplate, XrayConfig.class);

        List<InboundConfig> inbounds = xrayConfig.getInbounds().stream().filter(o -> o.getTag() != null && o.getTag().startsWith("default-")).collect(Collectors.toList());
        List<OutboundConfig> outbounds = xrayConfig.getOutbounds().stream().filter(o -> o.getTag() != null && o.getTag().startsWith("default-")).collect(Collectors.toList());
        List<RoutingRule> routingRules = xrayConfig.getRouting().getRules().stream().filter(o -> o.getRuleTag() != null && o.getRuleTag().startsWith("default-")).collect(Collectors.toList());

        if (server.getTransitConfig() != null && !server.getTransitConfig().equals("{}")) {
            // 服务器维度的配置
            XrayConfig transitConfig = JsonUtil.toObject(server.getTransitConfig(), XrayConfig.class);

            // 路由
            RoutingConfig routing = transitConfig.getRouting();
            if (routing != null && routing.getRules() != null) {
                routingRules.addAll(routing.getRules());
            }
        }

        for (Node node : enabledNodes) {
            processNodeConfiguration(node, inbounds, outbounds, routingRules);
        }

        xrayConfig.setInbounds(inbounds);
        xrayConfig.setOutbounds(outbounds);
        xrayConfig.getRouting().setRules(routingRules);

        return xrayConfig;
    }

    /**
     * 处理单个节点的配置
     */
    private void processNodeConfiguration(Node node, List<InboundConfig> inbounds, List<OutboundConfig> outbounds,
            List<RoutingRule> routingRules) {

        if (node.getInbound() == null) {
            log.warn("节点 {} 的入站配置为空，跳过处理", node.getId());
            return;
        }

        InboundConfig inbound = JsonUtil.toObject(node.getInbound(), InboundConfig.class);
        InboundSetting inboundSetting = inbound.getSettings();
        // 如果inboundSetting是VlessInboundSetting，则设置clients
        if (inboundSetting instanceof VlessInboundSetting vlessInboundSetting) {
            Set<Tag> tags = node.getTags();
            // 直接在数据库层面查询有效的账户，避免在应用层过滤
            List<Account> accounts = tagRepository.findActiveAccountsByTagIds(
                tags.stream().map(Tag::getId).collect(Collectors.toList()), 
                LocalDateTime.now()
            );
            // 过滤掉超过流量限制的用户
            accounts = accounts.stream().filter(account -> {
                Long usedBytes = accountTrafficRepository.sumBytesByAccountId(account.getId());
                if (usedBytes != null) {
                    long bandwidth = account.getBandwidth() * 1024L * 1024L * 1024L;
                    return usedBytes < bandwidth;
                }
                return true;
            }).collect(Collectors.toList());

            List<VlessClient> clients = accounts.stream().map(a -> {
                VlessClient client = new VlessClient();
                client.setId(a.getUuid());
                client.setEmail(a.getAccountNo());
                client.setFlow("xtls-rprx-vision");
                return client;
            }).collect(Collectors.toList());
            vlessInboundSetting.setClients(clients);
        }
        inbound.setTag(node.getTag());
        inbound.setPort(node.getPort());
        inbounds.add(inbound);

        if (node.getOutId() != null) {
            addOutboundConfiguration(node, outbounds);
        }

        addRoutingRule(node, routingRules);
    }

    /**
     * 添加出站配置
     */
    private void addOutboundConfiguration(Node node, List<OutboundConfig> outbounds) {
        Node outNode = node.getOutNode();
        if (outNode == null) {
            return;
        }
        for (OutboundConfig outbound : outbounds) {
            if (outbound.getTag().equals(outNode.getTag())) {
                return;
            }
        }
        InboundConfig outInbound = JsonUtil.toObject(outNode.getInbound(), InboundConfig.class);
        Server outServer = serverRepository.findById(outNode.getServerId());
        if (outServer == null) {
            throw new IllegalArgumentException("出站服务器不存在: " + outNode.getServerId());
        }

        ConversionStrategy strategy = strategyRegistry.getStrategy(outInbound.getProtocol());
        if (strategy != null) {
            OutboundConfig outbound = strategy.convert(outInbound, outServer.getIp(), outNode.getPort());
            outbound.setTag(outNode.getTag());
            outbounds.add(outbound);
        }
    }

    /**
     * 添加路由规则
     */
    private void addRoutingRule(Node node, List<RoutingRule> routingRules) {
        Node outNode = node.getOutNode();
        if (outNode == null) {
            return;
        }
        RoutingRule routingRule = new RoutingRule();
        routingRule.setInboundTag(Collections.singletonList(node.getTag()));
        routingRule.setOutboundTag(outNode.getTag());
        routingRule.setType("field");
        routingRules.add(routingRule);
    }

    /**
     * 生成Hysteria配置
     */
    private String generateHysteriaConfig(Node node) {
        // 实现Hysteria配置生成逻辑
        Map<String, Object> config = new HashMap<>();
        if (node.getInbound() != null) {
            config = JsonUtil.toObject(node.getInbound(), Map.class);
        }
        config.put("listen", ":" + node.getPort());
        return JsonUtil.toJsonString(config);
    }

    /**
     * 保存服务器配置
     */
    private ServerConfig saveServerConfig(Server server, String coreType, Object config) {
        Long serverId = server.getId();
        ServerConfig serverConfig = serverConfigRepository.findByServerIdAndConfigType(serverId, coreType)
                .orElse(createNewServerConfig(serverId, coreType));

        if (config instanceof XrayConfig) {
            serverConfig.setConfig(JsonUtil.toJsonStringPretty(config));
        } else {
            serverConfig.setConfig(config.toString());
        }
        serverConfigRepository.persist(serverConfig);
        return serverConfig;
    }

    /**
     * 创建新的服务器配置
     */
    private ServerConfig createNewServerConfig(Long serverId, String coreType) {
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.setServerId(serverId);
        serverConfig.setConfigType(coreType);
        serverConfig.setCreateTime(LocalDateTime.now());
        serverConfig.setPath("xray".equalsIgnoreCase(coreType) ? "/usr/local/etc/xray/config.json" : "/etc/hysteria/config.json");
        return serverConfig;
    }

    /**
     * 部署配置到服务器
     */
    private void deployConfigToServer(Server server, String coreType, String config) {
        SshConfig sshConfig = createSshConfig(server);

        // 上传配置
        CoreManagementResult configResult = coreManagementService.executeOperation(coreType, CoreOperation.CONFIG,
                sshConfig, config);

        if (configResult == null || !configResult.isSuccess()) {
            throw new RuntimeException("配置上传失败: " + (configResult != null ? configResult.getMessage() : "未知错误"));
        }

        // 重启服务
        CoreManagementResult restartResult = coreManagementService.executeOperation(coreType, CoreOperation.RESTART,
                sshConfig);

        if (restartResult == null || !restartResult.isSuccess()) {
            throw new RuntimeException("服务重启失败: " + (restartResult != null ? restartResult.getMessage() : "未知错误"));
        }

        log.info("服务器 {} 的 {} 配置部署成功", server.getName(), coreType);
    }

    /**
     * 创建SSH配置
     */
    private SshConfig createSshConfig(Server server) {
        SshConfig sshConfig = new SshConfig();
        sshConfig.setHost(server.getIp());
        sshConfig.setPort(server.getSshPort());
        sshConfig.setUsername(Objects.toString(server.getUsername(), DEFAULT_USERNAME));

        // 现在 auth 字段通过 JPA 转换器自动解密，直接使用即可
        String auth = server.getAuth();
        
        if ("PASSWORD".equalsIgnoreCase(server.getAuthType()) || "password".equalsIgnoreCase(server.getAuthType())) {
            sshConfig.setPassword(auth);
        } else {
            sshConfig.setPrivateKeyContent(auth);
        }

        return sshConfig;
    }

    /**
     * 更新节点部署状态
     */
    private List<DeploymentResult> updateNodeDeploymentStatus(List<Node> nodes, Long serverId) {
        List<DeploymentResult> results = new ArrayList<>();

        for (Node node : nodes) {
            try {
                results.add(updateSingleNodeDeploymentStatus(node, serverId));
            } catch (Exception e) {
                log.error("更新节点 {} 状态失败", node.getId(), e);
                results.add(createFailureResult(node, "状态更新失败: " + e.getMessage()));
            }
        }

        return results;
    }

    /**
     * 更新单个节点的部署状态
     */
    private DeploymentResult updateSingleNodeDeploymentStatus(Node node, Long serverId) {
        try {
            // 只有当部署的服务器是节点的主服务器时才更新部署状态
            if (serverId.equals(node.getServerId())) {
                node.setDeployed(1);
                nodeRepository.persist(node);
            }

            return createSuccessResult(node, "节点部署成功");
        } catch (Exception e) {
            log.error("更新节点 {} 部署状态失败", node.getId(), e);
            return createFailureResult(node, "状态更新失败: " + e.getMessage());
        }
    }

    /**
     * 确定核心类型
     */
    private String determineCoreType(String protocol) {
        return PROTOCOL_HYSTERIA2.equalsIgnoreCase(protocol) ? CORE_TYPE_HYSTERIA : CORE_TYPE_XRAY;
    }

    /**
     * 创建成功的部署结果
     */
    private DeploymentResult createSuccessResult(Node node, String message) {
        DeploymentResult result = new DeploymentResult();
        result.setNodeId(node.getId());
        result.setServerId(node.getServerId());
        result.setSuccess(true);
        result.setMessage(message);
        return result;
    }

    /**
     * 创建失败的部署结果
     */
    private DeploymentResult createFailureResult(Node node, String message) {
        DeploymentResult result = new DeploymentResult();
        result.setNodeId(node.getId());
        result.setServerId(node.getServerId());
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }

    /**
     * 批量创建失败的部署结果
     */
    private List<DeploymentResult> createFailureResults(List<Node> nodes, String message) {
        return nodes.stream().map(node -> createFailureResult(node, message)).collect(Collectors.toList());
    }
}