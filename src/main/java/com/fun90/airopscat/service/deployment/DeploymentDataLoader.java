package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.setting.inbound.VlessInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.VlessInboundSetting.VlessClient;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.AccountTrafficStatsRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.model.dto.deployment.DeploymentPreload;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.dto.deployment.ServerSnapshot;
import com.fun90.airopscat.model.dto.deployment.XrayNodeSnapshot;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
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
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class DeploymentDataLoader {

    private static final String CORE_TYPE_XRAY = "xray";
    private static final String PROTOCOL_HYSTERIA2 = "hysteria2";

    private final NodeRepository nodeRepository;
    private final ServerRepository serverRepository;
    private final TagRepository tagRepository;
    private final AccountTrafficStatsRepository accountTrafficRepository;

    public DeploymentPreload load(List<Node> inputNodes) {
        List<Long> targetServerIds = collectTargetServerIds(inputNodes);
        List<Node> relatedNodes = nodeRepository.findByServerIdsOrBackupServerIds(targetServerIds);

        Map<Long, Server> serverMap = loadServerMap(targetServerIds, relatedNodes);
        ensureServersExist(targetServerIds, serverMap);

        Map<Long, XrayNodeSnapshot> xraySnapshotMap = buildXraySnapshotMap(relatedNodes, serverMap);
        Map<Long, List<Node>> nodesByServerId = groupNodesByDeploymentServer(targetServerIds, relatedNodes);

        Map<Long, DeploymentServerContext> serverContexts = new LinkedHashMap<>();
        for (Long serverId : targetServerIds) {
            Server server = serverMap.get(serverId);
            serverContexts.put(serverId, new DeploymentServerContext(
                    server,
                    nodesByServerId.getOrDefault(serverId, Collections.emptyList()),
                    new ServerSnapshot(server.getTransitConfig()),
                    xraySnapshotMap
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

    private Map<Long, Server> loadServerMap(List<Long> targetServerIds, List<Node> relatedNodes) {
        List<Node> xrayNodes = filterXrayNodes(relatedNodes);

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
                throw new IllegalArgumentException("服务器不存在, serverId: " + serverId);
            }
        }
    }

    private Map<Long, XrayNodeSnapshot> buildXraySnapshotMap(List<Node> relatedNodes, Map<Long, Server> serverMap) {
        List<Node> xrayNodes = filterXrayNodes(relatedNodes);
        if (xrayNodes.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Long> outNodeIds = xrayNodes.stream()
                .map(Node::getOutId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Node> outNodeMap = nodeRepository.findByIdIn(outNodeIds).stream()
                .collect(Collectors.toMap(Node::getId, node -> node));

        Map<Long, List<VlessClient>> nodeClientsMap = buildNodeClientsMap(xrayNodes);

        Map<Long, XrayNodeSnapshot> snapshots = new HashMap<>();
        for (Node node : xrayNodes) {
            Node outNode = outNodeMap.get(node.getOutId());
            if (outNode != null && !serverMap.containsKey(outNode.getServerId())) {
                throw new IllegalArgumentException("出站服务器不存在: " + outNode.getServerId());
            }
            Server outServer = outNode == null ? null : serverMap.get(outNode.getServerId());
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

    private Map<Long, List<VlessClient>> buildNodeClientsMap(List<Node> xrayNodes) {
        LocalDateTime now = LocalDateTime.now();
        List<Long> xrayNodeIds = xrayNodes.stream().map(Node::getId).toList();
        Map<Long, List<Long>> nodeTagIdsMap = tagRepository.findTagIdsByNodeIds(xrayNodeIds);

        List<Long> allTagIds = nodeTagIdsMap.values().stream()
                .flatMap(List::stream)
                .distinct()
                .toList();

        Map<Long, Account> accountMap = tagRepository.findActiveAccountsByTagIds(allTagIds, now).stream()
                .collect(Collectors.toMap(Account::getId, a -> a, (left, right) -> left));

        Map<Long, List<Long>> tagAccountIdsMap = tagRepository.findAccountIdsByTagIds(allTagIds);
        Map<Long, Long> accountUsageMap = accountTrafficRepository.sumBytesByAccountIds(
                new ArrayList<>(accountMap.keySet()), now);

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
        VlessClient client = new VlessClient();
        client.setId(account.getUuid());
        client.setEmail(account.getAccountNo());
        client.setFlow("xtls-rprx-vision");
        return client;
    }

    private List<Node> filterXrayNodes(List<Node> nodes) {
        return nodes.stream()
                .filter(node -> CORE_TYPE_XRAY.equals(determineCoreType(node.getProtocol())))
                .toList();
    }

    private String determineCoreType(String protocol) {
        return PROTOCOL_HYSTERIA2.equalsIgnoreCase(protocol) ? "hysteria" : CORE_TYPE_XRAY;
    }
}