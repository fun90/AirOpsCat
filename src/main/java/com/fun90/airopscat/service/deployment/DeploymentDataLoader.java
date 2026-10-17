package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.deployment.DeploymentPreload;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.dto.deployment.NodeDeploymentSnapshot;
import com.fun90.airopscat.model.dto.deployment.RouteRuleSnapshot;
import com.fun90.airopscat.model.dto.deployment.ServerSnapshot;
import com.fun90.airopscat.model.dto.deployment.NodeClient;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.NodeServerRepository;
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

    private static final String CORE_TYPE_SING_BOX = "sing-box";

    private final NodeRepository nodeRepository;
    private final ServerRepository serverRepository;
    private final TagRepository tagRepository;
    private final RouteRuleService routeRuleService;
    private final NodeServerRepository nodeServerRepository;
    public DeploymentPreload load(List<NodeServer> inputPlacements) {
        List<Long> targetServerIds = inputPlacements.stream()
                .map(NodeServer::getServerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, List<NodeServer>> placementsByServerId = nodeServerRepository.findByServerIds(targetServerIds).stream()
                .collect(Collectors.groupingBy(NodeServer::getServerId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<RouteRuleSnapshot>> routeRulesByServerId = routeRuleService.getEnabledSnapshotsByServerIds(targetServerIds);
        Map<Long, Server> serverMap = serverRepository.findByIdIn(targetServerIds).stream()
                .collect(Collectors.toMap(Server::getId, server -> server));
        ensureServersExist(targetServerIds, serverMap);

        Map<Long, DeploymentServerContext> serverContexts = new LinkedHashMap<>();
        for (Long serverId : targetServerIds) {
            serverContexts.put(serverId, buildServerContext(
                    serverMap.get(serverId),
                    placementsByServerId.getOrDefault(serverId, Collections.emptyList()),
                    routeRulesByServerId.getOrDefault(serverId, Collections.emptyList())));
        }

        return new DeploymentPreload(targetServerIds, serverContexts);
    }

    public DeploymentServerContext loadForServer(Long serverId) {
        Server server = serverRepository.findById(serverId);
        if (server == null) {
            throw new IllegalArgumentException("服务器不存在, serverId: " + serverId);
        }
        List<RouteRuleSnapshot> routeRuleSnapshots = routeRuleService.getEnabledSnapshotsByServerIds(List.of(serverId))
                .getOrDefault(serverId, Collections.emptyList());
        return buildServerContext(server, nodeServerRepository.findByServerId(serverId), routeRuleSnapshots);
    }

    private DeploymentServerContext buildServerContext(Server server,
                                                       List<NodeServer> placements,
                                                       List<RouteRuleSnapshot> routeRuleSnapshots) {
        List<NodeServer> deployablePlacements = placements.stream()
                .filter(placement -> !NodeDeploymentStatus.isPendingDelete(placement.getDeployed()))
                .toList();
        return new DeploymentServerContext(
                server,
                placements,
                new ServerSnapshot(server.getTransitConfig(), routeRuleSnapshots),
                buildNodeSnapshotMap(server, deployablePlacements, routeRuleSnapshots)
        );
    }

    private void ensureServersExist(List<Long> targetServerIds, Map<Long, Server> serverMap) {
        for (Long serverId : targetServerIds) {
            if (!serverMap.containsKey(serverId)) {
                throw new IllegalArgumentException("服务器不存在, serverId: " + serverId);
            }
        }
    }

    /**
     * 同一服务器上 nodeId 唯一，快照按 nodeId 建；路由规则引用的落地节点取其生效部署位的服务器。
     */
    private Map<Long, NodeDeploymentSnapshot> buildNodeSnapshotMap(Server server,
                                                                   List<NodeServer> placements,
                                                                   List<RouteRuleSnapshot> routeRuleSnapshots) {
        Map<Long, NodeServer> placementByNodeId = placements.stream()
                .collect(Collectors.toMap(NodeServer::getNodeId, placement -> placement, (left, right) -> left, LinkedHashMap::new));
        Set<Long> routeNodeIds = routeRuleSnapshots.stream()
                .map(RouteRuleSnapshot::outboundNodeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<Long> snapshotNodeIds = new LinkedHashSet<>(placementByNodeId.keySet());
        snapshotNodeIds.addAll(routeNodeIds);
        if (snapshotNodeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Node> nodeMap = nodeRepository.findByIdIn(new ArrayList<>(snapshotNodeIds)).stream()
                .collect(Collectors.toMap(Node::getId, node -> node));

        Set<Long> outNodeIds = new LinkedHashSet<>(routeNodeIds);
        placementByNodeId.keySet().stream()
                .map(nodeMap::get)
                .filter(Objects::nonNull)
                .map(Node::getOutId)
                .filter(Objects::nonNull)
                .forEach(outNodeIds::add);
        List<Long> missingOutNodeIds = outNodeIds.stream().filter(id -> !nodeMap.containsKey(id)).toList();
        nodeRepository.findByIdIn(missingOutNodeIds).forEach(node -> nodeMap.put(node.getId(), node));
        Map<Long, NodeServer> effectiveOutPlacements = new HashMap<>();
        nodeServerRepository.groupByNodeIds(new ArrayList<>(outNodeIds)).forEach((nodeId, nodePlacements) ->
                NodeServerRepository.resolveEffective(nodePlacements)
                        .ifPresent(placement -> effectiveOutPlacements.put(nodeId, placement)));

        List<Node> localNodes = placementByNodeId.keySet().stream().map(nodeMap::get).filter(Objects::nonNull).toList();
        Map<Long, List<NodeClient>> nodeClientsMap = buildNodeClientsMap(localNodes);

        Map<Long, NodeDeploymentSnapshot> snapshots = new HashMap<>();
        for (Node node : localNodes) {
            NodeServer placement = placementByNodeId.get(node.getId());
            boolean disabled = Objects.equals(node.getDisabled(), 1) || !placement.enabledFlag();
            snapshots.put(node.getId(), toSnapshot(node, server.getIp(), disabled ? 1 : 0,
                    nodeMap.get(node.getOutId()), effectiveOutPlacements.get(node.getOutId()),
                    nodeClientsMap.getOrDefault(node.getId(), Collections.emptyList())));
        }
        for (Long routeNodeId : routeNodeIds) {
            Node node = nodeMap.get(routeNodeId);
            NodeServer effective = effectiveOutPlacements.get(routeNodeId);
            if (node == null || effective == null || snapshots.containsKey(routeNodeId)) {
                continue;
            }
            // 路由出站只用到节点自身的入站与地址
            snapshots.put(routeNodeId, toSnapshot(node, serverIp(effective), node.getDisabled(),
                    null, null, Collections.emptyList()));
        }
        return snapshots;
    }

    private NodeDeploymentSnapshot toSnapshot(Node node,
                                              String serverIp,
                                              Integer disabled,
                                              Node outNode,
                                              NodeServer outPlacement,
                                              List<NodeClient> clients) {
        if (outNode != null && outPlacement == null) {
            throw new IllegalArgumentException("出站节点没有可用的部署服务器: " + outNode.getId());
        }
        return new NodeDeploymentSnapshot(
                node.getId(),
                node.getCoreType(),
                node.getProtocol(),
                serverIp,
                node.getPort(),
                disabled,
                node.getInbound(),
                node.getOutId(),
                node.getTag(),
                outNode == null ? null : outNode.getCoreType(),
                outNode == null ? null : outNode.getProtocol(),
                outNode == null ? null : outNode.getTag(),
                outNode == null ? null : outNode.getInbound(),
                outNode == null ? null : serverIp(outPlacement),
                outNode == null ? null : outNode.getPort(),
                clients
        );
    }

    private String serverIp(NodeServer placement) {
        return placement == null || placement.getServer() == null ? null : placement.getServer().getIp();
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
                    .map(this::toVlessClient)
                    .toList();
            result.put(node.getId(), clients);
        }

        return result;
    }

    private NodeClient toVlessClient(Account account) {
        return new NodeClient(account.getUuid(), account.getAccountNo(), "xtls-rprx-vision",
                account.getDownloadMbps(), account.getUploadMbps(), account.getMaxIps());
    }

    private boolean supportsManagedClients(Node node) {
        String coreType = node.getCoreType();
        return CORE_TYPE_SING_BOX.equals(coreType);
    }
}
