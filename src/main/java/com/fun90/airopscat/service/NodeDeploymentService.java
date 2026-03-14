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
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.repository.AccountTrafficStatsRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.TagRepository;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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
            log.info("No nodes found for account ids {}", accountIdList);
            return Collections.emptyList();
        }
        return deployNodesForcibly(nodes);
    }

    @Transactional
    public List<DeploymentResult> deployNodes(List<Long> nodeIds) {
        try {
            List<Node> undeployedNodes = getUndeployedNodes(nodeIds);
            if (undeployedNodes.isEmpty()) {
                log.info("No undeployed nodes found");
                return Collections.emptyList();
            }
            return processNodesByServer(undeployedNodes);
        } catch (Exception e) {
            log.error("Deploy nodes failed", e);
            throw new RuntimeException("节点部署失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public List<DeploymentResult> deployNodesForcibly(List<Node> nodes) {
        try {
            return processNodesByServer(nodes);
        } catch (Exception e) {
            log.error("Force deploy nodes failed", e);
            throw new RuntimeException("节点部署失败: " + e.getMessage(), e);
        }
    }

    private List<Node> getUndeployedNodes(List<Long> nodeIds) {
        if (nodeIds != null && !nodeIds.isEmpty()) {
            return nodeRepository.findByDeployedAndIdIn(0, nodeIds);
        }
        return nodeRepository.findByDeployed(0);
    }

    private List<DeploymentResult> processNodesByServer(List<Node> nodes) {
        DeploymentPreload preload = preloadDeploymentData(nodes);
        List<CompletableFuture<List<CoreDeploymentExecution>>> futures = new ArrayList<>();

        for (Long serverId : preload.targetServerIds()) {
            DeploymentServerContext serverContext = preload.serverContexts().get(serverId);
            if (serverContext == null || serverContext.nodes().isEmpty()) {
                continue;
            }
            if (serverContext.server().getDisabled() == 1) {
                log.info("Skip disabled server {}", serverContext.server().getName());
                continue;
            }
            futures.add(CompletableFuture.supplyAsync(() -> deployNodesForServer(serverContext)));
        }

        List<DeploymentResult> results = new ArrayList<>();
        for (CompletableFuture<List<CoreDeploymentExecution>> future : futures) {
            for (CoreDeploymentExecution execution : future.join()) {
                results.addAll(persistDeploymentExecution(execution));
            }
        }
        return results;
    }

    private DeploymentPreload preloadDeploymentData(List<Node> inputNodes) {
        List<Long> targetServerIds = collectTargetServerIds(inputNodes);
        List<Node> relatedNodes = nodeRepository.findByServerIdsOrBackupServerIds(targetServerIds);
        Map<Long, List<Node>> nodesByServerId = groupNodesByDeploymentServer(targetServerIds, relatedNodes);

        List<Node> xrayNodes = relatedNodes.stream()
                .filter(node -> CORE_TYPE_XRAY.equals(determineCoreType(node.getProtocol())))
                .toList();

        List<Long> outNodeIds = xrayNodes.stream()
                .map(Node::getOutId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Node> outNodeMap = nodeRepository.findByIdIn(outNodeIds).stream()
                .collect(Collectors.toMap(Node::getId, node -> node));

        Set<Long> allServerIds = new LinkedHashSet<>(targetServerIds);
        outNodeMap.values().stream()
                .map(Node::getServerId)
                .filter(Objects::nonNull)
                .forEach(allServerIds::add);
        Map<Long, Server> serverMap = serverRepository.findByIdIn(new ArrayList<>(allServerIds)).stream()
                .collect(Collectors.toMap(Server::getId, server -> server));

        ensureServersExist(targetServerIds, serverMap);

        Map<Long, XrayNodeSnapshot> xraySnapshotMap = buildXraySnapshotMap(xrayNodes, outNodeMap, serverMap);

        Map<Long, DeploymentServerContext> serverContexts = new LinkedHashMap<>();
        for (Long serverId : targetServerIds) {
            Server server = serverMap.get(serverId);
            if (server == null) {
                throw new IllegalArgumentException("服务器不存在, serverId: " + serverId);
            }
            serverContexts.put(serverId, new DeploymentServerContext(
                    server,
                    nodesByServerId.getOrDefault(serverId, Collections.emptyList()),
                    createServerSnapshot(server),
                    xraySnapshotMap
            ));
        }

        return new DeploymentPreload(targetServerIds, serverContexts);
    }

    private List<Long> collectTargetServerIds(List<Node> nodes) {
        Set<Long> serverIds = new LinkedHashSet<>();
        nodes.forEach(node -> {
            serverIds.add(node.getServerId());
            if (node.getBackupServerId() != null) {
                serverIds.add(node.getBackupServerId());
            }
        });
        return new ArrayList<>(serverIds);
    }

    private Map<Long, List<Node>> groupNodesByDeploymentServer(List<Long> targetServerIds, List<Node> relatedNodes) {
        Set<Long> targetServerIdSet = new LinkedHashSet<>(targetServerIds);
        Map<Long, List<Node>> grouped = new LinkedHashMap<>();
        for (Long serverId : targetServerIds) {
            grouped.put(serverId, new ArrayList<>());
        }

        for (Node node : relatedNodes) {
            if (targetServerIdSet.contains(node.getServerId())) {
                grouped.computeIfAbsent(node.getServerId(), key -> new ArrayList<>()).add(node);
            }
            if (node.getBackupServerId() != null && targetServerIdSet.contains(node.getBackupServerId())) {
                grouped.computeIfAbsent(node.getBackupServerId(), key -> new ArrayList<>()).add(node);
            }
        }
        return grouped;
    }

    private void ensureServersExist(List<Long> targetServerIds, Map<Long, Server> serverMap) {
        for (Long serverId : targetServerIds) {
            if (!serverMap.containsKey(serverId)) {
                throw new IllegalArgumentException("服务器不存在, serverId: " + serverId);
            }
        }
    }

    private Map<Long, XrayNodeSnapshot> buildXraySnapshotMap(List<Node> xrayNodes, Map<Long, Node> outNodeMap,
                                                             Map<Long, Server> serverMap) {
        LocalDateTime now = LocalDateTime.now();
        List<Long> xrayNodeIds = xrayNodes.stream().map(Node::getId).toList();
        Map<Long, List<Long>> nodeTagIdsMap = tagRepository.findTagIdsByNodeIds(xrayNodeIds);

        List<Long> allTagIds = nodeTagIdsMap.values().stream()
                .flatMap(List::stream)
                .distinct()
                .toList();

        List<Account> activeAccounts = tagRepository.findActiveAccountsByTagIds(allTagIds, now);
        Map<Long, Account> accountMap = activeAccounts.stream()
                .collect(Collectors.toMap(Account::getId, account -> account, (left, right) -> left));

        Map<Long, List<Long>> tagAccountIdsMap = tagRepository.findAccountIdsByTagIds(allTagIds);
        Map<Long, Long> accountUsageMap = accountTrafficRepository.sumBytesByAccountIds(
                new ArrayList<>(accountMap.keySet()), now);

        Map<Long, List<VlessClient>> nodeClientsMap = buildNodeClientsMap(
                xrayNodes, nodeTagIdsMap, tagAccountIdsMap, accountMap, accountUsageMap);

        Map<Long, XrayNodeSnapshot> snapshots = new HashMap<>();
        for (Node node : xrayNodes) {
            Node outNode = outNodeMap.get(node.getOutId());
            Server outServer = outNode == null ? null : serverMap.get(outNode.getServerId());
            if (outNode != null && outServer == null) {
                throw new IllegalArgumentException("出站服务器不存在: " + outNode.getServerId());
            }

            snapshots.put(node.getId(), new XrayNodeSnapshot(
                    node.getId(),
                    node.getPort(),
                    node.getDisabled(),
                    node.getInbound(),
                    node.getOutId(),
                    node.getTag(),
                    outNode == null ? null : outNode.getTag(),
                    outNode == null ? null : outNode.getInbound(),
                    outServer == null ? null : outServer.getIp(),
                    outNode == null ? null : outNode.getPort(),
                    nodeClientsMap.getOrDefault(node.getId(), Collections.emptyList())
            ));
        }
        return snapshots;
    }

    private Map<Long, List<VlessClient>> buildNodeClientsMap(List<Node> xrayNodes, Map<Long, List<Long>> nodeTagIdsMap,
                                                             Map<Long, List<Long>> tagAccountIdsMap,
                                                             Map<Long, Account> accountMap,
                                                             Map<Long, Long> accountUsageMap) {
        Map<Long, List<VlessClient>> result = new HashMap<>();

        for (Node node : xrayNodes) {
            if (node.getInbound() == null) {
                continue;
            }

            InboundConfig inbound = JsonUtil.toObject(node.getInbound(), InboundConfig.class);
            if (!(inbound.getSettings() instanceof VlessInboundSetting)) {
                continue;
            }

            Set<Long> accountIds = new LinkedHashSet<>();
            for (Long tagId : nodeTagIdsMap.getOrDefault(node.getId(), Collections.emptyList())) {
                accountIds.addAll(tagAccountIdsMap.getOrDefault(tagId, Collections.emptyList()));
            }

            List<VlessClient> clients = accountIds.stream()
                    .map(accountMap::get)
                    .filter(Objects::nonNull)
                    .filter(account -> isAccountWithinBandwidth(account, accountUsageMap.get(account.getId())))
                    .map(this::toVlessClient)
                    .toList();

            result.put(node.getId(), clients);
        }

        return result;
    }

    private boolean isAccountWithinBandwidth(Account account, Long usedBytes) {
        if (usedBytes == null || account.getBandwidth() == null) {
            return true;
        }
        long bandwidth = account.getBandwidth() * 1024L * 1024L * 1024L;
        return usedBytes < bandwidth;
    }

    private VlessClient toVlessClient(Account account) {
        VlessClient client = new VlessClient();
        client.setId(account.getUuid());
        client.setEmail(account.getAccountNo());
        client.setFlow("xtls-rprx-vision");
        return client;
    }

    private List<CoreDeploymentExecution> deployNodesForServer(DeploymentServerContext serverContext) {
        Server server = serverContext.server();
        List<Node> nodes = serverContext.nodes();
        log.info("Deploy nodes for server {}({}), count={}", server.getName(), server.getId(), nodes.size());

        Map<String, List<Node>> coreTypeNodeMap = nodes.stream()
                .collect(Collectors.groupingBy(node -> determineCoreType(node.getProtocol())));

        List<CoreDeploymentExecution> results = new ArrayList<>();
        for (Map.Entry<String, List<Node>> entry : coreTypeNodeMap.entrySet()) {
            results.addAll(deployNodesForCore(serverContext, entry.getKey(), entry.getValue()));
        }
        return results;
    }

    private List<CoreDeploymentExecution> deployNodesForCore(DeploymentServerContext serverContext, String coreType,
                                                             List<Node> nodes) {
        List<CoreDeploymentExecution> results = new ArrayList<>();
        if (CORE_TYPE_XRAY.equals(coreType)) {
            results.add(deployXrayNodes(serverContext, nodes));
        } else if (CORE_TYPE_HYSTERIA.equals(coreType)) {
            results.addAll(deployHysteriaNodes(serverContext.server(), nodes));
        } else {
            results.add(CoreDeploymentExecution.failure(serverContext.server(), coreType, nodes, "不支持的核心类型: " + coreType));
        }
        return results;
    }

    private CoreDeploymentExecution deployXrayNodes(DeploymentServerContext serverContext, List<Node> nodes) {
        Server server = serverContext.server();
        List<XrayNodeSnapshot> snapshots = nodes.stream()
                .map(Node::getId)
                .map(serverContext.xraySnapshotMap()::get)
                .filter(Objects::nonNull)
                .toList();

        XrayConfig xrayConfig = generateXrayConfig(serverContext.serverSnapshot(), snapshots);
        String configJson = JsonUtil.toJsonStringPretty(xrayConfig);

        if (server.getExternal() == null || server.getExternal() == 0) {
            deployConfigToServer(server, CORE_TYPE_XRAY, configJson);
        }
        return CoreDeploymentExecution.success(server, CORE_TYPE_XRAY, nodes, configJson);
    }

    private List<CoreDeploymentExecution> deployHysteriaNodes(Server server, List<Node> nodes) {
        List<CoreDeploymentExecution> results = new ArrayList<>();
        for (Node node : nodes) {
            try {
                String hysteriaConfig = generateHysteriaConfig(node);
                deployConfigToServer(server, CORE_TYPE_HYSTERIA, hysteriaConfig);
                results.add(CoreDeploymentExecution.success(server, CORE_TYPE_HYSTERIA,
                        Collections.singletonList(node), hysteriaConfig));
            } catch (Exception e) {
                log.error("Deploy hysteria node {} failed on server {}", node.getId(), server.getName(), e);
                results.add(CoreDeploymentExecution.failure(server, CORE_TYPE_HYSTERIA,
                        Collections.singletonList(node), e.getMessage()));
            }
        }
        return results;
    }

    private XrayConfig generateXrayConfig(ServerSnapshot server, List<XrayNodeSnapshot> nodes) {
        List<XrayNodeSnapshot> enabledNodes = nodes.stream().filter(node -> node.disabled() == 0).toList();
        String configTemplate = configFileReader.readFileContent("config/core/xray.json");
        XrayConfig xrayConfig = JsonUtil.toObject(configTemplate, XrayConfig.class);

        List<InboundConfig> inbounds = xrayConfig.getInbounds().stream()
                .filter(config -> config.getTag() != null && config.getTag().startsWith("default-"))
                .collect(Collectors.toList());
        List<OutboundConfig> outbounds = xrayConfig.getOutbounds().stream()
                .filter(config -> config.getTag() != null && config.getTag().startsWith("default-"))
                .collect(Collectors.toList());
        List<RoutingRule> routingRules = xrayConfig.getRouting().getRules().stream()
                .filter(rule -> rule.getRuleTag() != null && rule.getRuleTag().startsWith("default-"))
                .collect(Collectors.toList());

        for (XrayNodeSnapshot node : enabledNodes) {
            processNodeConfiguration(node, inbounds, outbounds, routingRules);
        }

        if (server.transitConfig() != null && !server.transitConfig().equals("{}")) {
            XrayConfig transitConfig = JsonUtil.toObject(server.transitConfig(), XrayConfig.class);

            List<OutboundConfig> serverOutbounds = transitConfig.getOutbounds();
            List<String> tags = outbounds.stream().map(OutboundConfig::getTag).toList();
            if (serverOutbounds != null && !serverOutbounds.isEmpty()) {
                serverOutbounds = serverOutbounds.stream()
                        .filter(outbound -> !tags.contains(outbound.getTag()))
                        .toList();
                outbounds.addAll(serverOutbounds);
            }

            RoutingConfig routing = transitConfig.getRouting();
            if (routing != null && routing.getRules() != null) {
                routingRules.addAll(routing.getRules());
            }
        }

        xrayConfig.setInbounds(inbounds);
        xrayConfig.setOutbounds(outbounds);
        xrayConfig.getRouting().setRules(routingRules);
        return xrayConfig;
    }

    private void processNodeConfiguration(XrayNodeSnapshot node, List<InboundConfig> inbounds,
                                          List<OutboundConfig> outbounds, List<RoutingRule> routingRules) {
        if (node.inbound() == null) {
            log.warn("Node {} inbound config is null, skip", node.id());
            return;
        }

        InboundConfig inbound = JsonUtil.toObject(node.inbound(), InboundConfig.class);
        InboundSetting inboundSetting = inbound.getSettings();
        if (inboundSetting instanceof VlessInboundSetting vlessInboundSetting) {
            vlessInboundSetting.setClients(node.clients());
        }

        inbound.setTag(node.tag());
        inbound.setPort(node.port());
        inbounds.add(inbound);

        if (node.outId() != null) {
            addOutboundConfiguration(node, outbounds);
        }
        addRoutingRule(node, routingRules);
    }

    private void addOutboundConfiguration(XrayNodeSnapshot node, List<OutboundConfig> outbounds) {
        if (node.outInbound() == null || node.outTag() == null || node.outServerIp() == null || node.outPort() == null) {
            return;
        }

        for (OutboundConfig outbound : outbounds) {
            if (outbound.getTag().equals(node.outTag())) {
                return;
            }
        }

        InboundConfig outInbound = JsonUtil.toObject(node.outInbound(), InboundConfig.class);
        ConversionStrategy strategy = strategyRegistry.getStrategy(outInbound.getProtocol());
        if (strategy != null) {
            OutboundConfig outbound = strategy.convert(outInbound, node.outServerIp(), node.outPort());
            outbound.setTag(node.outTag());
            outbounds.add(outbound);
        }
    }

    private void addRoutingRule(XrayNodeSnapshot node, List<RoutingRule> routingRules) {
        if (node.outTag() == null) {
            return;
        }
        RoutingRule routingRule = new RoutingRule();
        routingRule.setInboundTag(Collections.singletonList(node.tag()));
        routingRule.setOutboundTag(node.outTag());
        routingRule.setType("field");
        routingRules.add(routingRule);
    }

    private String generateHysteriaConfig(Node node) {
        Map<String, Object> config = new HashMap<>();
        if (node.getInbound() != null) {
            config = JsonUtil.toObject(node.getInbound(), Map.class);
        }
        config.put("listen", ":" + node.getPort());
        return JsonUtil.toJsonString(config);
    }

    private ServerConfig saveServerConfig(Server server, String coreType, Object config) {
        Long serverId = server.getId();
        ServerConfig serverConfig = serverConfigRepository.findByServerIdAndConfigType(serverId, coreType)
                .orElse(createNewServerConfig(serverId, coreType));
        serverConfig.setConfig(config.toString());
        serverConfigRepository.persist(serverConfig);
        return serverConfig;
    }

    private ServerConfig createNewServerConfig(Long serverId, String coreType) {
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.setServerId(serverId);
        serverConfig.setConfigType(coreType);
        serverConfig.setCreateTime(LocalDateTime.now());
        serverConfig.setPath(CORE_TYPE_XRAY.equalsIgnoreCase(coreType)
                ? "/usr/local/etc/xray/config.json"
                : "/etc/hysteria/config.json");
        return serverConfig;
    }

    private void deployConfigToServer(Server server, String coreType, String config) {
        SshConfig sshConfig = createSshConfig(server);

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

    private SshConfig createSshConfig(Server server) {
        SshConfig sshConfig = new SshConfig();
        sshConfig.setHost(server.getIp());
        sshConfig.setPort(server.getSshPort());
        sshConfig.setUsername(Objects.toString(server.getUsername(), DEFAULT_USERNAME));

        String auth = server.getAuth();
        if ("PASSWORD".equalsIgnoreCase(server.getAuthType()) || "password".equalsIgnoreCase(server.getAuthType())) {
            sshConfig.setPassword(auth);
        } else {
            sshConfig.setPrivateKeyContent(auth);
        }
        return sshConfig;
    }

    private List<DeploymentResult> updateNodeDeploymentStatus(List<Node> nodes, Long serverId) {
        List<DeploymentResult> results = new ArrayList<>();
        for (Node node : nodes) {
            try {
                results.add(updateSingleNodeDeploymentStatus(node, serverId));
            } catch (Exception e) {
                log.error("Update node {} deployment status failed", node.getId(), e);
                results.add(createFailureResult(node, "状态更新失败: " + e.getMessage()));
            }
        }
        return results;
    }

    private DeploymentResult updateSingleNodeDeploymentStatus(Node node, Long serverId) {
        try {
            if (serverId.equals(node.getServerId())) {
                node.setDeployed(1);
                nodeRepository.persist(node);
            }
            return createSuccessResult(node, "节点部署成功");
        } catch (Exception e) {
            log.error("Update node {} deployed status failed", node.getId(), e);
            return createFailureResult(node, "状态更新失败: " + e.getMessage());
        }
    }

    private String determineCoreType(String protocol) {
        return PROTOCOL_HYSTERIA2.equalsIgnoreCase(protocol) ? CORE_TYPE_HYSTERIA : CORE_TYPE_XRAY;
    }

    private DeploymentResult createSuccessResult(Node node, String message) {
        DeploymentResult result = new DeploymentResult();
        result.setNodeId(node.getId());
        result.setServerId(node.getServerId());
        result.setSuccess(true);
        result.setMessage(message);
        return result;
    }

    private DeploymentResult createFailureResult(Node node, String message) {
        DeploymentResult result = new DeploymentResult();
        result.setNodeId(node.getId());
        result.setServerId(node.getServerId());
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }

    private List<DeploymentResult> createFailureResults(List<Node> nodes, String message) {
        return nodes.stream().map(node -> createFailureResult(node, message)).collect(Collectors.toList());
    }

    private List<DeploymentResult> persistDeploymentExecution(CoreDeploymentExecution execution) {
        if (!execution.success()) {
            return createFailureResults(execution.nodes(), execution.message());
        }
        saveServerConfig(execution.server(), execution.coreType(), execution.config());
        return new ArrayList<>(updateNodeDeploymentStatus(execution.nodes(), execution.server().getId()));
    }

    private ServerSnapshot createServerSnapshot(Server server) {
        return new ServerSnapshot(server.getTransitConfig());
    }

    private record DeploymentPreload(
            List<Long> targetServerIds,
            Map<Long, DeploymentServerContext> serverContexts
    ) {
    }

    private record DeploymentServerContext(
            Server server,
            List<Node> nodes,
            ServerSnapshot serverSnapshot,
            Map<Long, XrayNodeSnapshot> xraySnapshotMap
    ) {
    }

    private record ServerSnapshot(String transitConfig) {
    }

    private record XrayNodeSnapshot(
            Long id,
            Integer port,
            Integer disabled,
            String inbound,
            Long outId,
            String tag,
            String outTag,
            String outInbound,
            String outServerIp,
            Integer outPort,
            List<VlessClient> clients
    ) {
    }

    private record CoreDeploymentExecution(
            Server server,
            String coreType,
            List<Node> nodes,
            boolean success,
            String config,
            String message
    ) {
        private static CoreDeploymentExecution success(Server server, String coreType, List<Node> nodes, String config) {
            return new CoreDeploymentExecution(server, coreType, nodes, true, config, null);
        }

        private static CoreDeploymentExecution failure(Server server, String coreType, List<Node> nodes, String message) {
            return new CoreDeploymentExecution(server, coreType, nodes, false, null, message);
        }
    }
}
