package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.deployment.DeploymentPreload;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.dto.deployment.NodeDeploymentSnapshot;
import com.fun90.airopscat.model.dto.deployment.RouteRuleSnapshot;
import com.fun90.airopscat.model.dto.deployment.ServerSnapshot;
import com.fun90.airopscat.model.dto.deployment.VlessClient;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.AccountTrafficStatsRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.service.RouteRuleService;
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
                nodeRepository.findByServerIdsOrBackupServerIds(targetServerIds),
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

    private List<Long> collectTargetServerIds(List<Node> nodes) {
        Set<Long> serverIds = new LinkedHashSet<>();
        for (Node node : nodes) {
            serverIds.add(node.getServerId());
            if (node.getBackupServerId() != null) {
                serverIds.add(node.getBackupServerId());
            }
        }
        return new ArrayList<>(serverIds);
    }

    private Map<Long, Set<String>> collectTargetCoreTypesByServerId(List<Node> nodes) {
        Map<Long, Set<String>> coreTypesByServerId = new LinkedHashMap<>();
        for (Node node : nodes) {
            String coreType = normalizeCoreType(node.getCoreType());
            coreTypesByServerId
                    .computeIfAbsent(node.getServerId(), key -> new LinkedHashSet<>())
                    .add(coreType);
            if (node.getBackupServerId() != null) {
                coreTypesByServerId
                        .computeIfAbsent(node.getBackupServerId(), key -> new LinkedHashSet<>())
                        .add(coreType);
            }
        }
        return coreTypesByServerId;
    }

    private List<Node> filterRelatedNodesByServerCore(List<Node> nodes, Map<Long, Set<String>> targetCoreTypesByServerId) {
        return nodes.stream()
                .filter(node -> matchesTargetServerCore(node.getServerId(), node.getCoreType(), targetCoreTypesByServerId)
                        || matchesTargetServerCore(node.getBackupServerId(), node.getCoreType(), targetCoreTypesByServerId))
                .toList();
    }

    private boolean matchesTargetServerCore(Long serverId, String coreType, Map<Long, Set<String>> targetCoreTypesByServerId) {
        if (serverId == null) {
            return false;
        }
        Set<String> allowedCoreTypes = targetCoreTypesByServerId.get(serverId);
        return allowedCoreTypes != null && allowedCoreTypes.contains(normalizeCoreType(coreType));
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
            if (node.getBackupServerId() != null && targetServerIdSet.contains(node.getBackupServerId())) {
                grouped.computeIfAbsent(node.getBackupServerId(), id -> new ArrayList<>()).add(node);
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

        Map<Long, List<VlessClient>> nodeClientsMap = buildNodeClientsMap(snapshotNodes);

        Map<Long, NodeDeploymentSnapshot> snapshots = new HashMap<>();
        for (Node node : snapshotNodes) {
            Node outNode = outNodeMap.get(node.getOutId());
            if (outNode != null && !serverMap.containsKey(outNode.getServerId())) {
                throw new IllegalArgumentException("鍑虹珯鏈嶅姟鍣ㄤ笉瀛樺湪: " + outNode.getServerId());
            }

            Server server = node.getServerId() == null ? null : serverMap.get(node.getServerId());
            Server outServer = outNode == null ? null : serverMap.get(outNode.getServerId());
            snapshots.put(node.getId(), new NodeDeploymentSnapshot(
                    node.getId(),
                    normalizeCoreType(node.getCoreType()),
                    node.getProtocol(),
                    server == null ? null : server.getIp(),
                    node.getPort(),
                    node.getDisabled(),
                    node.getInbound(),
                    node.getOutId(),
                    node.getTag(),
                    outNode == null ? null : normalizeCoreType(outNode.getCoreType()),
                    outNode == null ? null : outNode.getProtocol(),
                    outNode == null ? null : outNode.getTag(),
                    outNode == null ? null : outNode.getInbound(),
                    outServer == null ? null : outServer.getIp(),
                    outNode == null ? null : outNode.getPort(),
                    nodeClientsMap.getOrDefault(node.getId(), Collections.emptyList())
            ));
        }
        return snapshots;
    }

    private Map<Long, List<VlessClient>> buildNodeClientsMap(List<Node> nodes) {
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

        Map<Long, List<VlessClient>> result = new HashMap<>();
        for (Node node : nodesWithManagedClients) {
            if (node.getInbound() == null) {
                continue;
            }

            Set<Long> accountIds = new LinkedHashSet<>();
            for (Long tagId : nodeTagIdsMap.getOrDefault(node.getId(), Collections.emptyList())) {
                accountIds.addAll(tagAccountIdsMap.getOrDefault(tagId, Collections.emptyList()));
            }

            List<VlessClient> clients = accountIds.stream()
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

    private VlessClient toVlessClient(Account account) {
        return new VlessClient(account.getUuid(), account.getAccountNo(), "xtls-rprx-vision");
    }

    private boolean supportsManagedClients(Node node) {
        String coreType = normalizeCoreType(node.getCoreType());
        return (CORE_TYPE_XRAY.equals(coreType) || CORE_TYPE_SING_BOX.equals(coreType));
    }

    private String normalizeCoreType(String coreType) {
        if (coreType == null || coreType.trim().isEmpty()) {
            return CORE_TYPE_XRAY;
        }
        return coreType.trim().toLowerCase();
    }
}
