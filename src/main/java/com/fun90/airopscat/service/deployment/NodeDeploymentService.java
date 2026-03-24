package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.DefaultConfigDto;
import com.fun90.airopscat.model.dto.NodeCoreSwitchResponse;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.model.enums.CoreType;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.model.enums.ProtocolType;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.model.dto.deployment.CoreDeploymentExecution;
import com.fun90.airopscat.model.dto.deployment.DeploymentPreload;
import com.fun90.airopscat.service.NodeService;
import com.fun90.airopscat.service.core.CoreManagementService;
import com.fun90.airopscat.service.deployment.registry.CoreConfigBuilderRegistry;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class NodeDeploymentService {

    private final NodeRepository nodeRepository;
    private final ServerRepository serverRepository;
    private final TagRepository tagRepository;
    private final NodeService nodeService;
    private final CoreManagementService coreManagementService;
    private final DeploymentDataLoader dataLoader;
    private final CoreDeploymentExecutor deploymentExecutor;
    private final CoreConfigBuilderRegistry coreConfigBuilderRegistry;

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

    @Transactional
    public NodeCoreSwitchResponse switchNodeCore(List<Long> nodeIds, String targetCoreType, Boolean redeploy) {
        List<Long> uniqueNodeIds = nodeIds == null ? List.of() : nodeIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (uniqueNodeIds.isEmpty()) {
            throw new IllegalArgumentException("请选择至少一个节点");
        }

        String normalizedTargetCoreType = normalizeSupportedCoreType(targetCoreType);
        List<Node> nodes = nodeRepository.findByIdIn(uniqueNodeIds);
        if (nodes.size() != uniqueNodeIds.size()) {
            throw new EntityNotFoundException("存在无效的节点选择");
        }

        NodeCoreSwitchResponse response = new NodeCoreSwitchResponse();
        response.setTargetCoreType(normalizedTargetCoreType);
        response.setRequestedCount(uniqueNodeIds.size());
        response.setRedeployed(Boolean.TRUE.equals(redeploy));

        Set<String> sourceCoreTypes = nodes.stream()
                .map(Node::getCoreType)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (sourceCoreTypes.size() != 1) {
            throw new IllegalArgumentException("所选节点必须属于同一种原内核后才能切换");
        }
        String sourceCoreType = sourceCoreTypes.iterator().next();
        if (sourceCoreType.equals(normalizedTargetCoreType)) {
            throw new IllegalArgumentException("目标内核必须不同于原内核");
        }
        validateNoLandingNodesForCoreSwitch(nodes);

        Set<Long> affectedServerIds = new LinkedHashSet<>();
        Map<Long, Set<String>> sourceCoresByServerId = new LinkedHashMap<>();
        Map<Long, Node> outboundNodeMap = loadOutboundNodeMap(nodes);
        for (Node node : nodes) {
            collectSourceCores(node, sourceCoresByServerId);
            validateNodeCoreSwitch(node, normalizedTargetCoreType, outboundNodeMap);
            Map<String, Object> translatedInbound = translateInboundConfig(node, normalizedTargetCoreType);

            node.setCoreType(normalizedTargetCoreType);
            node.setInbound(JsonUtil.toJsonString(translatedInbound));
            node.setDeployed(0);
            response.getSwitchedNodeIds().add(node.getId());
            collectNodeServers(node, affectedServerIds);
        }

        response.setSwitchedCount(response.getSwitchedNodeIds().size());
        response.setUnchangedCount(0);

        if (Boolean.TRUE.equals(redeploy) && !affectedServerIds.isEmpty()) {
            stopSourceCoresBeforeRedeploy(sourceCoresByServerId);
            List<Node> affectedNodes = nodeRepository.findByServerIdsOrBackupServerIds(new ArrayList<>(affectedServerIds));
            List<DeploymentResult> deploymentResults = deployNodesForcibly(affectedNodes);
            response.setDeploymentResults(deploymentResults);
        }

        return response;
    }

    private void validateNoLandingNodesForCoreSwitch(List<Node> nodes) {
        List<Long> landingNodeIds = nodes.stream()
                .filter(node -> Objects.equals(node.getType(), NodeType.LANDING.getValue()))
                .map(Node::getId)
                .toList();
        if (!landingNodeIds.isEmpty()) {
            throw new IllegalArgumentException("落地节点暂不支持切换内核。节点: "
                    + landingNodeIds);
        }
    }

    private List<Node> getUndeployedNodes(List<Long> nodeIds) {
        if (nodeIds != null && !nodeIds.isEmpty()) {
            return nodeRepository.findByDeployedAndIdIn(0, nodeIds);
        }
        return nodeRepository.findByDeployed(0);
    }

    private List<DeploymentResult> processNodesByServer(List<Node> nodes) {
        DeploymentPreload preload = dataLoader.load(nodes);
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();

        List<CompletableFuture<List<CoreDeploymentExecution>>> futures = preload.serverContexts().values().stream()
                .filter(ctx -> !ctx.nodes().isEmpty())
                .filter(ctx -> ctx.server().getDisabled() != 1)
                .map(ctx -> CompletableFuture.supplyAsync(
                        withContextClassLoader(contextClassLoader, () -> deploymentExecutor.executeForServer(ctx))))
                .toList();

        List<DeploymentResult> results = new ArrayList<>();
        for (CompletableFuture<List<CoreDeploymentExecution>> future : futures) {
            for (CoreDeploymentExecution execution : future.join()) {
                results.addAll(deploymentExecutor.persist(execution));
            }
        }
        return results;
    }

    private <T> Supplier<T> withContextClassLoader(ClassLoader contextClassLoader, Supplier<T> supplier) {
        return () -> {
            Thread currentThread = Thread.currentThread();
            ClassLoader originalClassLoader = currentThread.getContextClassLoader();
            try {
                currentThread.setContextClassLoader(contextClassLoader);
                return supplier.get();
            } finally {
                currentThread.setContextClassLoader(originalClassLoader);
            }
        };
    }

    public Map<String, String> previewServerConfigs(Long serverId) {
        DeploymentServerContext ctx = dataLoader.loadForServer(serverId);
        Server server = ctx.server();
        if (server == null) {
            return Collections.emptyMap();
        }

        Set<String> coreTypes = new LinkedHashSet<>();
        for (Node node : ctx.nodes()) {
            coreTypes.add(node.getCoreType());
        }
        if (ctx.serverSnapshot().routeRules() != null) {
            ctx.serverSnapshot().routeRules().stream()
                    .map(routeRule -> routeRule.coreType())
                    .forEach(coreTypes::add);
        }

        Map<String, String> configs = new LinkedHashMap<>();
        for (String coreType : coreTypes) {
            try {
                List<Node> coreNodes = ctx.nodes().stream()
                        .filter(node -> node.getCoreType().equals(coreType))
                        .toList();
                String config = coreConfigBuilderRegistry.getStrategy(coreType).build(ctx, coreNodes);
                configs.put(coreType, config);
            } catch (UnsupportedOperationException e) {
                log.warn("Skip preview for unsupported core {} on server {}", coreType, serverId);
            }
        }
        return configs;
    }

    private String normalizeSupportedCoreType(String coreType) {
        CoreType parsedCoreType = CoreType.fromValue(coreType);
        if (parsedCoreType == null || parsedCoreType == CoreType.HYSTERIA2) {
            throw new IllegalArgumentException("仅支持切换到 xray 或 sing-box");
        }
        return parsedCoreType.getValue();
    }

    private void validateNodeCoreSwitch(Node node, String targetCoreType, Map<Long, Node> outboundNodeMap) {
        if (node.getProtocol() == null || node.getProtocol().trim().isEmpty()) {
            throw new IllegalArgumentException("节点 " + node.getId() + " 缺少协议配置，无法切换内核");
        }
        if (!ProtocolType.isSupported(node.getProtocol(), node.getType(), targetCoreType)) {
            throw new IllegalArgumentException("节点 " + node.getId()
                    + " 的协议 " + node.getProtocol()
                    + " 不支持目标内核 " + targetCoreType);
        }
        if (node.getOutId() != null) {
            Node outboundNode = outboundNodeMap.get(node.getOutId());
            if (outboundNode == null) {
                throw new IllegalArgumentException("节点 " + node.getId() + " 的出站节点不存在，无法切换内核");
            }
            if (!targetCoreType.equals(outboundNode.getCoreType())) {
                String outboundNodeLabel = outboundNode.getName() == null || outboundNode.getName().isBlank()
                        ? String.valueOf(outboundNode.getId())
                        : outboundNode.getName() + "(" + outboundNode.getId() + ")";
                throw new IllegalArgumentException("节点 " + node.getId()
                        + " 已配置出站节点 " + outboundNodeLabel
                        + "，请先将该出站节点切换到 " + targetCoreType + " 内核后再试");
            }
        }
    }

    private Map<Long, Node> loadOutboundNodeMap(List<Node> nodes) {
        List<Long> outboundNodeIds = nodes.stream()
                .map(Node::getOutId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (outboundNodeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return nodeRepository.findByIdIn(outboundNodeIds).stream()
                .collect(Collectors.toMap(Node::getId, outboundNode -> outboundNode));
    }

    private void collectNodeServers(Node node, Set<Long> affectedServerIds) {
        if (node.getServerId() != null) {
            affectedServerIds.add(node.getServerId());
        }
        if (node.getBackupServerId() != null) {
            affectedServerIds.add(node.getBackupServerId());
        }
    }

    private void collectSourceCores(Node node, Map<Long, Set<String>> sourceCoresByServerId) {
        String sourceCoreType = node.getCoreType();
        if (node.getServerId() != null) {
            sourceCoresByServerId
                    .computeIfAbsent(node.getServerId(), key -> new LinkedHashSet<>())
                    .add(sourceCoreType);
        }
        if (node.getBackupServerId() != null) {
            sourceCoresByServerId
                    .computeIfAbsent(node.getBackupServerId(), key -> new LinkedHashSet<>())
                    .add(sourceCoreType);
        }
    }

    private void stopSourceCoresBeforeRedeploy(Map<Long, Set<String>> sourceCoresByServerId) {
        for (Map.Entry<Long, Set<String>> entry : sourceCoresByServerId.entrySet()) {
            Server server = serverRepository.findById(entry.getKey());
            if (server == null || (server.getExternal() != null && server.getExternal() == 1)) {
                continue;
            }

            SshConfig sshConfig = buildSshConfig(server);
            for (String coreType : entry.getValue()) {
                List<CoreManagementResult> results = coreManagementService.executeOperations(
                        coreType,
                        sshConfig,
                        new CoreManagementService.OperationRequest(CoreOperation.STOP)
                );
                CoreManagementResult stopResult = results.isEmpty() ? null : results.getFirst();
                if (stopResult == null || !stopResult.isSuccess()) {
                    throw new RuntimeException("停止原内核失败: serverId=" + server.getId()
                            + ", coreType=" + coreType
                            + ", message=" + (stopResult != null ? stopResult.getMessage() : "未知错误"));
                }
            }
        }
    }

    private SshConfig buildSshConfig(Server server) {
        SshConfig sshConfig = new SshConfig();
        sshConfig.setHost(server.getIp());
        sshConfig.setPort(server.getSshPort());
        sshConfig.setUsername(Objects.toString(server.getUsername(), "root"));

        boolean isPassword = "PASSWORD".equalsIgnoreCase(server.getAuthType())
                || "password".equalsIgnoreCase(server.getAuthType());
        if (isPassword) {
            sshConfig.setPassword(server.getAuth());
        } else {
            sshConfig.setPrivateKeyContent(server.getAuth());
        }
        return sshConfig;
    }

    private Map<String, Object> translateInboundConfig(Node node, String targetCoreType) {
        DefaultConfigDto<Map<String, Object>> defaultConfig =
                nodeService.generateDefaultInbound(node.getProtocol(), node.getServerId(), node.getAccessHostId(), targetCoreType);
        Map<String, Object> targetInbound = copyMap(defaultConfig.getConfig());
        Map<String, Object> sourceInbound = parseInbound(node.getInbound());

        String protocol = node.getProtocol() == null ? "" : node.getProtocol().trim().toLowerCase(Locale.ROOT);
        return switch (targetCoreType) {
            case "xray" -> translateInboundToXray(protocol, sourceInbound, targetInbound);
            case "sing-box" -> translateInboundToSingBox(protocol, sourceInbound, targetInbound);
            default -> throw new IllegalArgumentException("不支持的目标内核: " + targetCoreType);
        };
    }

    private Map<String, Object> translateInboundToXray(String protocol,
                                                       Map<String, Object> sourceInbound,
                                                       Map<String, Object> targetInbound) {
        switch (protocol) {
            case "vless", "vless-reality" -> copyVlessToXray(sourceInbound, targetInbound);
            case "shadowsocks" -> copyShadowsocksToXray(sourceInbound, targetInbound);
            case "socks" -> copySocksToXray(sourceInbound, targetInbound);
            default -> {
            }
        }
        return targetInbound;
    }

    private Map<String, Object> translateInboundToSingBox(String protocol,
                                                          Map<String, Object> sourceInbound,
                                                          Map<String, Object> targetInbound) {
        switch (protocol) {
            case "vless", "vless-reality" -> copyVlessToSingBox(sourceInbound, targetInbound);
            case "shadowsocks" -> copyShadowsocksToSingBox(sourceInbound, targetInbound);
            case "socks" -> copySocksToSingBox(sourceInbound, targetInbound);
            default -> {
            }
        }
        return targetInbound;
    }

    private void copyVlessToSingBox(Map<String, Object> sourceInbound, Map<String, Object> targetInbound) {
        List<Map<String, Object>> clients = asMapList(asMap(sourceInbound.get("settings")).get("clients"));
        if (!clients.isEmpty()) {
            List<Map<String, Object>> users = new ArrayList<>();
            for (Map<String, Object> client : clients) {
                Map<String, Object> user = new LinkedHashMap<>();
                putIfNotNull(user, "uuid", client.get("id"));
                putIfNotNull(user, "name", client.get("email"));
                putIfNotNull(user, "flow", client.get("flow"));
                users.add(user);
            }
            targetInbound.put("users", users);
        }

        Map<String, Object> streamSettings = asMap(sourceInbound.get("streamSettings"));
        String security = Objects.toString(streamSettings.get("security"), "").trim().toLowerCase(Locale.ROOT);
        Map<String, Object> targetTls = ensureMap(targetInbound, "tls");

        if ("reality".equals(security)) {
            Map<String, Object> realitySettings = asMap(streamSettings.get("realitySettings"));
            String serverName = firstString(asList(realitySettings.get("serverNames")));
            if (serverName != null) {
                targetTls.put("server_name", serverName);
            }

            Map<String, Object> reality = ensureMap(targetTls, "reality");
            reality.put("enabled", true);
            Map<String, Object> handshake = ensureMap(reality, "handshake");
            String dest = Objects.toString(realitySettings.get("dest"), "");
            putDestination(handshake, dest, serverName);
            putIfNotNull(reality, "private_key", realitySettings.get("privateKey"));
            putIfNotNull(reality, "public_key", realitySettings.get("publicKey"));
            Object shortIds = realitySettings.get("shortIds");
            if (shortIds instanceof List<?> list && !list.isEmpty()) {
                reality.put("short_id", new ArrayList<>(list));
            }
            return;
        }

        Map<String, Object> tlsSettings = asMap(streamSettings.get("tlsSettings"));
        putIfNotNull(targetTls, "min_version", tlsSettings.get("minVersion"));
        if (tlsSettings.get("alpn") instanceof List<?> list && !list.isEmpty()) {
            targetTls.put("alpn", new ArrayList<>(list));
        }
    }

    private void copyVlessToXray(Map<String, Object> sourceInbound, Map<String, Object> targetInbound) {
        List<Map<String, Object>> users = asMapList(sourceInbound.get("users"));
        if (!users.isEmpty()) {
            List<Map<String, Object>> clients = new ArrayList<>();
            for (Map<String, Object> user : users) {
                Map<String, Object> client = new LinkedHashMap<>();
                putIfNotNull(client, "id", user.get("uuid"));
                putIfNotNull(client, "email", user.get("name"));
                putIfNotNull(client, "flow", user.get("flow"));
                client.put("level", 0);
                clients.add(client);
            }
            Map<String, Object> settings = ensureMap(targetInbound, "settings");
            settings.put("clients", clients);
        }

        Map<String, Object> sourceTls = asMap(sourceInbound.get("tls"));
        Map<String, Object> sourceReality = asMap(sourceTls.get("reality"));
        Map<String, Object> targetStreamSettings = ensureMap(targetInbound, "streamSettings");

        if (Boolean.TRUE.equals(sourceReality.get("enabled"))) {
            targetStreamSettings.put("security", "reality");
            Map<String, Object> realitySettings = ensureMap(targetStreamSettings, "realitySettings");
            Map<String, Object> handshake = asMap(sourceReality.get("handshake"));
            String serverName = Objects.toString(sourceTls.get("server_name"), null);
            if (serverName != null && !serverName.isBlank()) {
                realitySettings.put("serverNames", List.of(serverName));
            }
            realitySettings.put("dest", joinDestination(
                    Objects.toString(handshake.get("server"), serverName),
                    toInteger(handshake.get("server_port"), 443)));
            putIfNotNull(realitySettings, "privateKey", sourceReality.get("private_key"));
            putIfNotNull(realitySettings, "publicKey", sourceReality.get("public_key"));
            Object shortId = sourceReality.get("short_id");
            if (shortId instanceof List<?> list && !list.isEmpty()) {
                realitySettings.put("shortIds", new ArrayList<>(list));
            } else if (shortId instanceof String value && !value.isBlank()) {
                realitySettings.put("shortIds", List.of(value));
            }
            return;
        }

        targetStreamSettings.put("security", "tls");
        Map<String, Object> tlsSettings = ensureMap(targetStreamSettings, "tlsSettings");
        putIfNotNull(tlsSettings, "minVersion", sourceTls.get("min_version"));
        if (sourceTls.get("alpn") instanceof List<?> list && !list.isEmpty()) {
            tlsSettings.put("alpn", new ArrayList<>(list));
        }
    }

    private void copyShadowsocksToSingBox(Map<String, Object> sourceInbound, Map<String, Object> targetInbound) {
        Map<String, Object> settings = asMap(sourceInbound.get("settings"));
        putIfNotNull(targetInbound, "method", settings.get("method"));
        putIfNotNull(targetInbound, "password", settings.get("password"));
        putIfNotNull(targetInbound, "network", settings.get("network"));
    }

    private void copyShadowsocksToXray(Map<String, Object> sourceInbound, Map<String, Object> targetInbound) {
        Map<String, Object> settings = ensureMap(targetInbound, "settings");
        putIfNotNull(settings, "method", sourceInbound.get("method"));
        putIfNotNull(settings, "password", sourceInbound.get("password"));
        putIfNotNull(settings, "network", sourceInbound.get("network"));
    }

    private void copySocksToSingBox(Map<String, Object> sourceInbound, Map<String, Object> targetInbound) {
        Map<String, Object> settings = asMap(sourceInbound.get("settings"));
        List<Map<String, Object>> accounts = asMapList(settings.get("accounts"));
        if (!accounts.isEmpty()) {
            List<Map<String, Object>> users = new ArrayList<>();
            for (Map<String, Object> account : accounts) {
                Map<String, Object> user = new LinkedHashMap<>();
                putIfNotNull(user, "username", account.get("user"));
                putIfNotNull(user, "password", account.get("pass"));
                users.add(user);
            }
            targetInbound.put("users", users);
        }
    }

    private void copySocksToXray(Map<String, Object> sourceInbound, Map<String, Object> targetInbound) {
        List<Map<String, Object>> users = asMapList(sourceInbound.get("users"));
        Map<String, Object> settings = ensureMap(targetInbound, "settings");
        if (!users.isEmpty()) {
            List<Map<String, Object>> accounts = new ArrayList<>();
            for (Map<String, Object> user : users) {
                Map<String, Object> account = new LinkedHashMap<>();
                putIfNotNull(account, "user", user.get("username"));
                putIfNotNull(account, "pass", user.get("password"));
                accounts.add(account);
            }
            settings.put("accounts", accounts);
            settings.put("auth", "password");
        }
    }

    private Map<String, Object> parseInbound(String inboundJson) {
        if (inboundJson == null || inboundJson.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> inbound = JsonUtil.toObject(inboundJson, Map.class);
        return inbound == null ? new LinkedHashMap<>() : inbound;
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return new LinkedHashMap<>();
        }
        return JsonUtil.toObject(JsonUtil.toJsonString(source), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> newMap = new LinkedHashMap<>();
        parent.put(key, newMap);
        return newMap;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String firstString(List<Object> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        String value = Objects.toString(list.getFirst(), null);
        return value == null || value.isBlank() ? null : value;
    }

    private void putDestination(Map<String, Object> handshake, String dest, String fallbackServerName) {
        String server = fallbackServerName;
        Integer port = 443;
        if (dest != null && !dest.isBlank()) {
            int index = dest.lastIndexOf(':');
            if (index > 0) {
                server = dest.substring(0, index);
                port = toInteger(dest.substring(index + 1), 443);
            } else {
                server = dest;
            }
        }
        if (server != null && !server.isBlank()) {
            handshake.put("server", server);
        }
        handshake.put("server_port", port);
    }

    private String joinDestination(String server, Integer port) {
        String normalizedServer = (server == null || server.isBlank()) ? "www.apple.com" : server;
        int normalizedPort = port == null ? 443 : port;
        return normalizedServer + ":" + normalizedPort;
    }

    private Integer toInteger(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
