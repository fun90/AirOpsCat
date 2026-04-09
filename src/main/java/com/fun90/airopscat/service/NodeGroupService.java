package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.enums.CoreType;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@ApplicationScoped
public class NodeGroupService {

    @Inject
    NodeRepository nodeRepository;

    public String normalizeNodeGroup(String nodeGroup) {
        if (nodeGroup == null) {
            return null;
        }
        String normalized = nodeGroup.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public List<Node> findGroupNodes(String nodeGroup) {
        String normalizedNodeGroup = normalizeNodeGroup(nodeGroup);
        if (normalizedNodeGroup == null) {
            return List.of();
        }
        return nodeRepository.findByNodeGroup(normalizedNodeGroup);
    }

    public List<Node> findPeerNodes(String nodeGroup, Long excludeId) {
        String normalizedNodeGroup = normalizeNodeGroup(nodeGroup);
        if (normalizedNodeGroup == null) {
            return List.of();
        }
        return nodeRepository.findByNodeGroupAndIdNot(normalizedNodeGroup, excludeId);
    }

    public List<Node> expandWithRelatedGroups(List<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        Map<Long, Node> expanded = new LinkedHashMap<>();
        LinkedHashSet<String> nodeGroups = new LinkedHashSet<>();
        for (Node node : nodes) {
            if (node == null || node.getId() == null) {
                continue;
            }
            expanded.put(node.getId(), node);
            String nodeGroup = normalizeNodeGroup(node.getNodeGroup());
            if (nodeGroup != null) {
                nodeGroups.add(nodeGroup);
            }
        }

        if (!nodeGroups.isEmpty()) {
            nodeRepository.findByNodeGroupIn(new ArrayList<>(nodeGroups))
                    .forEach(node -> expanded.put(node.getId(), node));
        }
        return new ArrayList<>(expanded.values());
    }

    public List<Node> validateNodeGroup(Node node) {
        String nodeGroup = normalizeNodeGroup(node == null ? null : node.getNodeGroup());
        if (nodeGroup == null) {
            return List.of();
        }

        List<Node> groupNodes = findPeerNodes(nodeGroup, node.getId());
        for (Node groupNode : groupNodes) {
            if (!Objects.equals(node.getType(), groupNode.getType())) {
                throw new IllegalArgumentException("节点组只能关联相同节点类型的节点");
            }
            if (!Objects.equals(normalizeCoreTypeValue(node.getCoreType()), normalizeCoreTypeValue(groupNode.getCoreType()))) {
                throw new IllegalArgumentException("节点组只能关联相同内核类型的节点");
            }
            if (node.getServerId() != null && Objects.equals(node.getServerId(), groupNode.getServerId())) {
                throw new IllegalArgumentException("节点组不能包含同一服务器上的节点");
            }
        }
        return groupNodes;
    }

    public void alignNodeToGroupConfiguration(Node node, List<Node> groupNodes) {
        if (node == null || groupNodes == null || groupNodes.isEmpty()) {
            return;
        }

        Node referenceNode = groupNodes.getFirst();
        node.setProtocol(referenceNode.getProtocol());
        node.setPort(referenceNode.getPort());
        node.setInbound(normalizeJson(referenceNode.getInbound()));
        node.setOutId(normalizeNullableLong(referenceNode.getOutId()));
    }

    public void markAffectedGroupNodesUndeployed(String previousGroup, String currentGroup, Long currentNodeId) {
        String normalizedPreviousGroup = normalizeNodeGroup(previousGroup);
        String normalizedCurrentGroup = normalizeNodeGroup(currentGroup);
        if (Objects.equals(normalizedPreviousGroup, normalizedCurrentGroup)) {
            return;
        }

        markGroupNodesUndeployed(normalizedPreviousGroup, currentNodeId);
        markGroupNodesUndeployed(normalizedCurrentGroup, currentNodeId);
    }

    public void markGroupNodesUndeployed(String nodeGroup, Long currentNodeId) {
        String normalizedNodeGroup = normalizeNodeGroup(nodeGroup);
        if (normalizedNodeGroup == null) {
            return;
        }

        findPeerNodes(normalizedNodeGroup, currentNodeId)
                .forEach(node -> node.setDeployed(0));
    }

    public void syncPeerNodesToReference(Node referenceNode, List<Node> peerNodes) {
        if (referenceNode == null || peerNodes == null || peerNodes.isEmpty()) {
            return;
        }
        for (Node peer : peerNodes) {
            peer.setProtocol(referenceNode.getProtocol());
            peer.setPort(referenceNode.getPort());
            peer.setInbound(normalizeJson(referenceNode.getInbound()));
            peer.setOutId(normalizeNullableLong(referenceNode.getOutId()));
            peer.setDeployed(0);
        }
    }

    public List<Map<String, Object>> getNodeGroupOptions(Integer type, String coreType, Long excludeId, Long serverId, String keyword) {
        if (type == null || coreType == null || coreType.trim().isEmpty()) {
            return List.of();
        }

        String normalizedCoreType = normalizeCoreTypeValue(coreType);
        String normalizedKeyword = keyword == null ? null : keyword.trim().toLowerCase();
        LinkedHashSet<String> groups = new LinkedHashSet<>();

        Node currentNode = excludeId == null ? null : nodeRepository.findById(excludeId);
        String currentNodeGroup = currentNode == null ? null : normalizeNodeGroup(currentNode.getNodeGroup());
        if (currentNodeGroup != null) {
            groups.add(currentNodeGroup);
        }

        nodeRepository.findNodeGroupCandidateNodes(type, normalizedCoreType, excludeId).stream()
                .map(node -> normalizeNodeGroup(node.getNodeGroup()))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(group -> group, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .filter(entry -> entry.getValue().stream().noneMatch(group -> hasServerConflict(group, serverId)))
                .map(Map.Entry::getKey)
                .filter(group -> normalizedKeyword == null || normalizedKeyword.isBlank() || group.toLowerCase().contains(normalizedKeyword))
                .limit(Math.max(10, groups.size()))
                .forEach(groups::add);

        return groups.stream()
                .limit(10)
                .map(this::toNodeGroupOption)
                .toList();
    }

    public Map<String, Object> getNodeGroupConfig(String nodeGroup, Integer type, String coreType, Long serverId, Long excludeId) {
        String normalizedNodeGroup = normalizeNodeGroup(nodeGroup);
        if (normalizedNodeGroup == null) {
            return Map.of("exists", false);
        }

        List<Node> groupNodes = findGroupNodes(normalizedNodeGroup);
        if (groupNodes.isEmpty()) {
            return Map.of("exists", false);
        }

        String normalizedCoreType = normalizeCoreTypeValue(coreType);
        List<Node> referenceCandidates = groupNodes.stream()
                .filter(node -> !Objects.equals(node.getId(), excludeId))
                .toList();
        boolean compatibleType = type != null && groupNodes.stream().allMatch(node -> Objects.equals(type, node.getType()));
        boolean compatibleCoreType = coreType != null && groupNodes.stream()
                .allMatch(node -> Objects.equals(normalizedCoreType, normalizeCoreTypeValue(node.getCoreType())));
        boolean hasSameServer = serverId != null && referenceCandidates.stream()
                .anyMatch(node -> Objects.equals(serverId, node.getServerId()));
        boolean lockConfig = !referenceCandidates.isEmpty();
        Node referenceNode = lockConfig ? referenceCandidates.getFirst() : groupNodes.getFirst();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("compatible", compatibleType && compatibleCoreType && !hasSameServer);
        result.put("lockConfig", lockConfig);
        if (!compatibleType) {
            result.put("message", "该节点组包含不同节点类型的节点，无法关联");
            return result;
        }
        if (!compatibleCoreType) {
            result.put("message", "该节点组包含不同内核类型的节点，无法关联");
            return result;
        }
        if (hasSameServer) {
            result.put("message", "该节点组已包含当前服务器上的节点，无法关联");
            return result;
        }

        result.put("protocol", referenceNode.getProtocol());
        result.put("port", referenceNode.getPort());
        result.put("outId", normalizeNullableLong(referenceNode.getOutId()));
        result.put("inbound", referenceNode.getInbound() == null || referenceNode.getInbound().isBlank()
                ? Map.of()
                : JsonUtil.toObject(referenceNode.getInbound(), Map.class));
        result.put("referenceNodeName", referenceNode.getName());
        return result;
    }

    private boolean hasServerConflict(String nodeGroup, Long serverId) {
        if (serverId == null) {
            return false;
        }
        return findGroupNodes(nodeGroup).stream()
                .anyMatch(node -> Objects.equals(serverId, node.getServerId()));
    }

    private Map<String, Object> toNodeGroupOption(String nodeGroup) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("value", nodeGroup);
        option.put("label", nodeGroup);
        return option;
    }

    private String normalizeCoreTypeValue(String coreType) {
        if (coreType == null || coreType.trim().isEmpty()) {
            return coreType;
        }
        CoreType parsedCoreType = CoreType.fromValue(coreType);
        return parsedCoreType == null ? coreType.trim().toLowerCase() : parsedCoreType.getValue();
    }

    private String normalizeJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return JsonUtil.toJsonString(JsonUtil.toObject(json, Map.class));
    }

    private Long normalizeNullableLong(Long value) {
        return value == null || value == 0 ? null : value;
    }
}
