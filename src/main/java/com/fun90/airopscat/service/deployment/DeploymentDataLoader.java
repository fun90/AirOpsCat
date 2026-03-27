package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.deployment.DeploymentPreload;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.dto.deployment.NodeDeploymentSnapshot;
import com.fun90.airopscat.model.dto.deployment.RouteRuleSnapshot;
import com.fun90.airopscat.model.dto.deployment.ServerSnapshot;
import com.fun90.airopscat.model.dto.deployment.NodeClient;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.AccountTrafficStatsRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.service.RouteRuleService;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;

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
import java.util.stream.Collectors;

@ApplicationScoped
@RequiredArgsConstructor
public class DeploymentDataLoader {

    private static final String CORE_TYPE_XRAY = "xray";
    private static final String CORE_TYPE_SING_BOX = "sing-box";

    private final NodeRepository nodeRepository;
    private final ServerRepository serverRepository;
    private final TagRepository tagRepository;
    private final AccountTrafficStatsRepository accountTrafficRepository;
    private final RouteRuleService routeRuleService;

    public DeploymentPreload load(List<Node> inputNodes) {
        List<Long> targetServerIds = collectTargetServerIds(inputNodes);
        Map<Long, Set<String>> targetCoreTypesByServerId = collectTargetCoreTypesByServerId(inputNodes);
        List<Node> relatedNodes = filterRelatedNodesByServerCore(
                nodeRepository.findByServerIdIn(targetServerIds),
                targetCoreTypesByServerId
        );
        Map<Long, List<RouteRuleSnapshot>> routeRulesByServerId = routeRuleService.getEnabledSnapshotsByServerIds(targetServerIds);
        List<RouteRuleSnapshot> routeRuleSnapshots = routeRulesByServerId.values().stream()
                .flatMap(List::stream)
                .distinct()
                .toList();

        Map<Long, Server> serverMap = loadServerMap(targetServerIds, relatedNodes, routeRuleSnapshots);
        ensureServersExist(targetServerIds, serverMap);

        Map<Long, NodeDeploymentSnapshot> nodeSnapshotMap = buildNodeSnapshotMap(relatedNodes, routeRuleSnapshots, serverMap);
        Map<Long, List<Node>> nodesByServerId = groupNodesByDeploymentServer(targetServerIds, relatedNodes);

        Map<Long, DeploymentServerContext> serverContexts = new LinkedHashMap<>();
        for (Long serverId : targetServerIds) {
            Server server = serverMap.get(serverId);
            serverContexts.put(serverId, new DeploymentServerContext(
                    server,
                    nodesByServerId.getOrDefault(serverId, Collections.emptyList()),
                    new ServerSnapshot(server.getTransitConfig(), routeRulesByServerId.getOrDefault(serverId, Collections.emptyList())),
                    nodeSnapshotMap
            ));
        }

        return new DeploymentPreload(targetServerIds, serverContexts);
    }

    public DeploymentServerContext loadForServer(Long serverId) {
        Server server = serverRepository.findById(serverId);
        if (server == null) {
            throw new IllegalArgumentException("鏈嶅姟鍣ㄤ笉瀛樺湪, serverId: " + serverId);
        }

        List<Long> targetServerIds = List.of(serverId);
        List<Node> relatedNodes = nodeRepository.findByServerId(serverId);
        Map<Long, List<RouteRuleSnapshot>> routeRulesByServerId = routeRuleService.getEnabledSnapshotsByServerIds(targetServerIds);
        List<RouteRuleSnapshot> routeRuleSnapshots = routeRulesByServerId.getOrDefault(serverId, Collections.emptyList());

        Map<Long, Set<String>> targetCoreTypesByServerId = collectTargetCoreTypesByServerId(relatedNodes);
        if (!routeRuleSnapshots.isEmpty()) {
            Set<String> coreTypes = targetCoreTypesByServerId.computeIfAbsent(serverId, key -> new LinkedHashSet<>());
            routeRuleSnapshots.stream()
                    .map(RouteRuleSnapshot::coreType)
                    .forEach(coreTypes::add);
        }

        List<Node> filteredNodes = targetCoreTypesByServerId.isEmpty()
                ? Collections.emptyList()
                : filterRelatedNodesByServerCore(relatedNodes, targetCoreTypesByServerId);

        Map<Long, Server> serverMap = loadServerMap(targetServerIds, filteredNodes, routeRuleSnapshots);
        ensureServersExist(targetServerIds, serverMap);

        Map<Long, NodeDeploymentSnapshot> nodeSnapshotMap = buildNodeSnapshotMap(filteredNodes, routeRuleSnapshots, serverMap);
        return new DeploymentServerContext(
                server,
                groupNodesByDeploymentServer(targetServerIds, filteredNodes).getOrDefault(serverId, Collections.emptyList()),
                new ServerSnapshot(server.getTransitConfig(), routeRuleSnapshots),
                nodeSnapshotMap
        );
    }

    private List<Long> collectTargetServerIds(List<Node> nodes) {
        Set<Long> serverIds = new LinkedHashSet<>();
        for (Node node : nodes) {
            serverIds.add(node.getServerId());
        }
        return new ArrayList<>(serverIds);
    }

    private Map<Long, Set<String>> collectTargetCoreTypesByServerId(List<Node> nodes) {
        Map<Long, Set<String>> coreTypesByServerId = new LinkedHashMap<>();
        for (Node node : nodes) {
            String coreType = node.getCoreType();
            coreTypesByServerId
                    .computeIfAbsent(node.getServerId(), key -> new LinkedHashSet<>())
                    .add(coreType);
        }
        return coreTypesByServerId;
    }

    private List<Node> filterRelatedNodesByServerCore(List<Node> nodes, Map<Long, Set<String>> targetCoreTypesByServerId) {
        return nodes.stream()
                .filter(node -> matchesTargetServerCore(node.getServerId(), node.getCoreType(), targetCoreTypesByServerId))
                .toList();
    }

    private boolean matchesTargetServerCore(Long serverId, String coreType, Map<Long, Set<String>> targetCoreTypesByServerId) {
        if (serverId == null) {
            return false;
        }
        Set<String> allowedCoreTypes = targetCoreTypesByServerId.get(serverId);
        return allowedCoreTypes != null && allowedCoreTypes.contains(coreType);
    }

    private Map<Long, Server> loadServerMap(List<Long> targetServerIds,
                                            List<Node> relatedNodes,
                                            List<RouteRuleSnapshot> routeRuleSnapshots) {
        Set<Long> outboundNodeIds = new LinkedHashSet<>(relatedNodes.stream()
                .map(Node::getOutId)
                .filter(Objects::nonNull)
                .toList());
        outboundNodeIds.addAll(routeRuleSnapshots.stream()
                .map(RouteRuleSnapshot::outboundNodeId)
                .filter(Objects::nonNull)
                .toList());

        Map<Long, Node> outNodeMap = nodeRepository.findByIdIn(new ArrayList<>(outboundNodeIds)).stream()
                .collect(Collectors.toMap(Node::getId, node -> node));

        Set<Long> allServerIds = new LinkedHashSet<>(targetServerIds);
        outNodeMap.values().stream()
                .map(Node::getServerId)
                .filter(Objects::nonNull)
                .forEach(allServerIds::add);

        return serverRepository.findByIdIn(new ArrayList<>(allServerIds)).stream()
                .collect(Collectors.toMap(Server::getId, server -> server));
    }

    private Map<Long, List<Node>> groupNodesByDeploymentServer(List<Long> targetServerIds, List<Node> relatedNodes) {
        Set<Long> targetServerIdSet = new LinkedHashSet<>(targetServerIds);
        Map<Long, List<Node>> grouped = new LinkedHashMap<>();
        for (Long serverId : targetServerIds) {
            grouped.put(serverId, new ArrayList<>());
        }
        for (Node node : relatedNodes) {
            if (targetServerIdSet.contains(node.getServerId())) {
                grouped.computeIfAbsent(node.getServerId(), id -> new ArrayList<>()).add(node);
            }
        }
        return grouped;
    }

    private void ensureServersExist(List<Long> targetServerIds, Map<Long, Server> serverMap) {
        for (Long serverId : targetServerIds) {
            if (!serverMap.containsKey(serverId)) {
                throw new IllegalArgumentException("鏈嶅姟鍣ㄤ笉瀛樺湪, serverId: " + serverId);
            }
        }
    }

    private Map<Long, NodeDeploymentSnapshot> buildNodeSnapshotMap(List<Node> relatedNodes,
                                                                   List<RouteRuleSnapshot> routeRuleSnapshots,
                                                                   Map<Long, Server> serverMap) {
        Set<Long> extraNodeIds = routeRuleSnapshots.stream()
                .map(RouteRuleSnapshot::outboundNodeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Node> snapshotNodeMap = relatedNodes.stream()
                .collect(Collectors.toMap(Node::getId, node -> node, (left, right) -> left, LinkedHashMap::new));
        if (!extraNodeIds.isEmpty()) {
            nodeRepository.findByIdIn(new ArrayList<>(extraNodeIds)).forEach(node -> snapshotNodeMap.putIfAbsent(node.getId(), node));
        }
        List<Node> snapshotNodes = new ArrayList<>(snapshotNodeMap.values());

        if (snapshotNodes.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Long> outNodeIds = snapshotNodes.stream()
                .map(Node::getOutId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Node> outNodeMap = nodeRepository.findByIdIn(outNodeIds).stream()
                .collect(Collectors.toMap(Node::getId, node -> node));

        Map<Long, List<NodeClient>> nodeClientsMap = buildNodeClientsMap(snapshotNodes);
        Map<Long, Node> primaryNodesByBackupNodeId = snapshotNodes.stream()
                .filter(node -> node.getBackupNodeId() != null)
                .collect(Collectors.toMap(Node::getBackupNodeId, node -> node, (left, right) -> left, LinkedHashMap::new));
        List<Long> backupNodeIds = snapshotNodes.stream()
                .map(Node::getId)
                .distinct()
                .toList();
        if (!backupNodeIds.isEmpty()) {
            nodeRepository.findByBackupNodeIdIn(backupNodeIds)
                    .forEach(node -> primaryNodesByBackupNodeId.putIfAbsent(node.getBackupNodeId(), node));
        }

        Map<Long, NodeDeploymentSnapshot> snapshots = new HashMap<>();
        for (Node node : snapshotNodes) {
            Node outNode = outNodeMap.get(node.getOutId());
            if (outNode != null && !serverMap.containsKey(outNode.getServerId())) {
                throw new IllegalArgumentException("鍑虹珯鏈嶅姟鍣ㄤ笉瀛樺湪: " + outNode.getServerId());
            }

            Server server = node.getServerId() == null ? null : serverMap.get(node.getServerId());
            Server outServer = outNode == null ? null : serverMap.get(outNode.getServerId());
            Node primaryNode = primaryNodesByBackupNodeId.get(node.getId());
            snapshots.put(node.getId(), new NodeDeploymentSnapshot(
                    node.getId(),
                    node.getCoreType(),
                    node.getProtocol(),
                    server == null ? null : server.getIp(),
                    node.getPort(),
                    node.getDisabled(),
                    mergeBackupInbound(node, primaryNode),
                    node.getOutId(),
                    node.getTag(),
                    outNode == null ? null : outNode.getCoreType(),
                    outNode == null ? null : outNode.getProtocol(),
                    outNode == null ? null : outNode.getTag(),
                    outNode == null ? null : outNode.getInbound(),
                    outServer == null ? null : outServer.getIp(),
                    outNode == null ? null : outNode.getPort(),
                    mergeClients(
                            nodeClientsMap.getOrDefault(node.getId(), Collections.emptyList()),
                            primaryNode == null ? Collections.emptyList() : nodeClientsMap.getOrDefault(primaryNode.getId(), Collections.emptyList())
                    )
            ));
        }
        return snapshots;
    }

    private String mergeBackupInbound(Node node, Node primaryNode) {
        if (primaryNode == null || node.getInbound() == null || primaryNode.getInbound() == null) {
            return node.getInbound();
        }

        Map<String, Object> backupInbound = toMap(node.getInbound());
        Map<String, Object> primaryInbound = toMap(primaryNode.getInbound());
        String protocol = Objects.toString(node.getProtocol(), "").trim().toLowerCase();

        if ("vless".equals(protocol) || "vless-reality".equals(protocol)) {
            Map<String, Object> backupSettings = ensureMap(backupInbound, "settings");
            Map<String, Object> primarySettings = asMap(primaryInbound.get("settings"));
            backupSettings.put("clients", mergeUserMaps(
                    asMapList(backupSettings.get("clients")),
                    asMapList(primarySettings == null ? null : primarySettings.get("clients")),
                    "id"
            ));
            return JsonUtil.toJsonString(backupInbound);
        }

        if ("hysteria2".equals(protocol) || "socks".equals(protocol) || "shadowsocks".equals(protocol)) {
            backupInbound.put("users", mergeUserMaps(
                    asMapList(backupInbound.get("users")),
                    asMapList(primaryInbound.get("users")),
                    "name",
                    "username",
                    "password"
            ));
            return JsonUtil.toJsonString(backupInbound);
        }

        return node.getInbound();
    }

    private List<NodeClient> mergeClients(List<NodeClient> currentClients, List<NodeClient> primaryClients) {
        Map<String, NodeClient> merged = new LinkedHashMap<>();
        for (NodeClient client : currentClients) {
            merged.put(client.id(), client);
        }
        for (NodeClient client : primaryClients) {
            merged.put(client.id(), client);
        }
        return new ArrayList<>(merged.values());
    }

    private List<Map<String, Object>> mergeUserMaps(List<Map<String, Object>> currentUsers,
                                                    List<Map<String, Object>> extraUsers,
                                                    String... preferredKeys) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> user : currentUsers) {
            merged.put(resolveUserKey(user, preferredKeys), new LinkedHashMap<>(user));
        }
        for (Map<String, Object> user : extraUsers) {
            merged.put(resolveUserKey(user, preferredKeys), new LinkedHashMap<>(user));
        }
        return new ArrayList<>(merged.values());
    }

    private String resolveUserKey(Map<String, Object> user, String... preferredKeys) {
        for (String preferredKey : preferredKeys) {
            Object value = user.get(preferredKey);
            if (value != null && !Objects.toString(value, "").isBlank()) {
                return preferredKey + ":" + value;
            }
        }
        return JsonUtil.toJsonString(user);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(String json) {
        Map<String, Object> map = JsonUtil.toObject(json, Map.class);
        return map == null ? new LinkedHashMap<>() : map;
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
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptyList();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private Map<Long, List<NodeClient>> buildNodeClientsMap(List<Node> nodes) {
        List<Node> nodesWithManagedClients = nodes.stream()
                .filter(this::supportsManagedClients)
                .toList();
        if (nodesWithManagedClients.isEmpty()) {
            return Collections.emptyMap();
        }

        LocalDateTime now = LocalDateTime.now();
        List<Long> nodeIds = nodesWithManagedClients.stream().map(Node::getId).toList();
        Map<Long, List<Long>> nodeTagIdsMap = tagRepository.findTagIdsByNodeIds(nodeIds);

        List<Long> allTagIds = nodeTagIdsMap.values().stream()
                .flatMap(List::stream)
                .distinct()
                .toList();

        Map<Long, Account> accountMap = tagRepository.findActiveAccountsByTagIds(allTagIds, now).stream()
                .collect(Collectors.toMap(Account::getId, account -> account, (left, right) -> left));

        Map<Long, List<Long>> tagAccountIdsMap = tagRepository.findAccountIdsByTagIds(allTagIds);
        Map<Long, Long> accountUsageMap = accountTrafficRepository.sumBytesByAccountIds(
                new ArrayList<>(accountMap.keySet()), now);

        Map<Long, List<NodeClient>> result = new HashMap<>();
        for (Node node : nodesWithManagedClients) {
            if (node.getInbound() == null) {
                continue;
            }

            Set<Long> accountIds = new LinkedHashSet<>();
            for (Long tagId : nodeTagIdsMap.getOrDefault(node.getId(), Collections.emptyList())) {
                accountIds.addAll(tagAccountIdsMap.getOrDefault(tagId, Collections.emptyList()));
            }

            List<NodeClient> clients = accountIds.stream()
                    .map(accountMap::get)
                    .filter(Objects::nonNull)
                    .filter(account -> isWithinBandwidth(account, accountUsageMap.get(account.getId())))
                    .map(this::toVlessClient)
                    .toList();
            result.put(node.getId(), clients);
        }

        return result;
    }

    private boolean isWithinBandwidth(Account account, Long usedBytes) {
        if (usedBytes == null || account.getBandwidth() == null) {
            return true;
        }
        long bandwidthBytes = account.getBandwidth() * 1024L * 1024L * 1024L;
        return usedBytes < bandwidthBytes;
    }

    private NodeClient toVlessClient(Account account) {
        return new NodeClient(account.getUuid(), account.getAccountNo(), "xtls-rprx-vision");
    }

    private boolean supportsManagedClients(Node node) {
        String coreType = node.getCoreType();
        return (CORE_TYPE_XRAY.equals(coreType) || CORE_TYPE_SING_BOX.equals(coreType));
    }
}
