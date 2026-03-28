package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.DefaultConfigDto;
import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.convert.NodeConverter;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerHost;
import com.fun90.airopscat.model.entity.Tag;
import com.fun90.airopscat.model.enums.CoreType;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.model.enums.ProtocolType;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.service.inbound.registry.DefaultInboundStrategyRegistry;
import com.fun90.airopscat.service.inbound.strategy.DefaultInboundStrategy;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class NodeService {
    @Inject
    NodeRepository nodeRepository;

    @Inject
    ServerRepository serverRepository;

    @Inject
    ServerHostService serverHostService;

    @Inject
    TagRepository tagRepository;

    @Inject
    DefaultInboundStrategyRegistry strategyRegistry;

    @Inject
    NodeGroupService nodeGroupService;

    public io.quarkus.hibernate.orm.panache.PanacheQuery<Node> getNodePage(
            String search,
            String serverIds,
            Long nodeTagId,
            Integer type,
            String coreType,
            String protocol,
            Boolean disabled,
            Boolean deployed,
            String sortBy,
            String sortOrder
    ) {
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (search != null && !search.trim().isEmpty()) {
            String searchLike = "%" + search.toLowerCase() + "%";
            query.append(" and (lower(name) like :search or lower(remark) like :search or lower(nodeGroup) like :search")
                 .append(" or serverId in (select id from Server where lower(ip) like :search or lower(host) like :search"
                         + " or id in (select sh.serverId from ServerHost sh where lower(sh.host) like :search))")
                 .append(" or accessHostId in (select id from ServerHost where lower(host) like :search)")
                 .append(")");
            params.put("search", searchLike);
        }

        if (serverIds != null && !serverIds.trim().isEmpty()) {
            String[] idArray = serverIds.split(",");
            if (idArray.length == 1) {
                query.append(" and serverId = :serverId");
                params.put("serverId", Long.parseLong(idArray[0].trim()));
            } else {
                List<Long> idList = Arrays.stream(idArray)
                        .map(String::trim)
                        .map(Long::parseLong)
                        .collect(Collectors.toList());
                query.append(" and serverId in :serverIds");
                params.put("serverIds", idList);
            }
        }

        if (nodeTagId != null) {
            query.append(" and id in (select n.id from Node n join n.tags t where t.id = :nodeTagId)");
            params.put("nodeTagId", nodeTagId);
        }

        if (type != null) {
            query.append(" and type = :type");
            params.put("type", type);
        }

        if (coreType != null && !coreType.trim().isEmpty()) {
            query.append(" and coreType = :coreType");
            params.put("coreType", coreType.trim());
        }

        if (protocol != null && !protocol.trim().isEmpty()) {
            query.append(" and protocol = :protocol");
            params.put("protocol", protocol.trim());
        }

        if (disabled != null) {
            query.append(" and disabled = :disabled");
            params.put("disabled", disabled ? 1 : 0);
        }

        if (deployed != null) {
            query.append(" and deployed = :deployed");
            params.put("deployed", deployed ? 1 : 0);
        }

        Sort sort = buildSort(sortBy, sortOrder);
        return nodeRepository.find(query.toString(), sort, params);
    }

    private Sort buildSort(String sortBy, String sortOrder) {
        boolean ascending = "asc".equalsIgnoreCase(sortOrder);

        if (sortBy == null || sortBy.trim().isEmpty()) {
            return Sort.by("createTime").descending();
        }

        return switch (sortBy) {
            case "name" -> ascending ? Sort.by("name", "no").ascending() : Sort.by("name", "no").descending();
            case "server" -> ascending ? Sort.by("serverId").ascending() : Sort.by("serverId").descending();
            case "nodeGroup" -> ascending ? Sort.by("nodeGroup", "name", "no").ascending() : Sort.by("nodeGroup", "name", "no").descending();
            case "outbound" -> ascending ? Sort.by("outId", "name", "no").ascending() : Sort.by("outId", "name", "no").descending();
            default -> Sort.by("createTime").descending();
        };
    }

    public Node getNodeById(Long id) {
        return nodeRepository.findById(id);
    }

    public NodeDto getNodeDtoById(Long id) {
        Node node = nodeRepository.findById(id);
        if (node == null) {
            return null;
        }
        return NodeConverter.toDto(node);
    }

    public List<NodeDto> toNodeDtos(List<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        return nodes.stream().map(NodeConverter::toDto).toList();
    }

    public List<Node> getNodeByType(NodeType nodeType) {
        return nodeRepository.findByType(nodeType.getValue());
    }

    public List<Node> getNodesByServer(Long serverId) {
        return nodeRepository.findByServerId(serverId);
    }

    public Map<String, Long> getNodesStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", nodeRepository.count());
        stats.put("proxy", nodeRepository.countProxyNodes());
        stats.put("landing", nodeRepository.countLandingNodes());
        stats.put("active", nodeRepository.countActiveNodes());
        stats.put("disabled", nodeRepository.countDisabledNodes());
        return stats;
    }

    public boolean isPortAvailable(Long serverId, Integer port, Long nodeId) {
        if (serverId == null || port == null) {
            return true;
        }
        if (nodeId == null) {
            return !nodeRepository.existsByServerIdAndPort(serverId, port);
        }
        return !nodeRepository.existsByServerIdAndPortAndIdNot(serverId, port, nodeId);
    }

    public boolean isNodePortsAvailable(Long serverId, Integer port, Long nodeId) {
        return isNodePortsAvailable(serverId, port, nodeId, List.of());
    }

    public boolean isNodePortsAvailable(Long serverId, Integer port, Long nodeId, List<Long> excludedIds) {
        if (serverId == null || port == null) {
            return true;
        }

        List<Long> effectiveExcludedIds = new ArrayList<>();
        if (excludedIds != null) {
            effectiveExcludedIds.addAll(excludedIds.stream().filter(Objects::nonNull).toList());
        }
        if (nodeId != null) {
            effectiveExcludedIds.add(nodeId);
        }
        return !nodeRepository.existsByServerIdAndPortAndIdNotIn(serverId, port, effectiveExcludedIds);
    }

    private void validateNodeServersAndPorts(Node node, List<Node> groupNodes) {
        if (nodeRepository.existsByNameAndNoAndIdNot(node.getName(), node.getNo(), node.getId())) {
            throw new IllegalArgumentException("名称与编号重复：" + node.getName() + " " + node.getNo());
        }

        if (node.getServerId() != null && serverRepository.findById(node.getServerId()) == null) {
            throw new EntityNotFoundException("Server with ID " + node.getServerId() + " not found");
        }

        if (node.getAccessHostId() != null) {
            ServerHost accessHost = serverHostService.getHostById(node.getAccessHostId());
            if (accessHost == null) {
                throw new EntityNotFoundException("Access host not found");
            }
            if (!Objects.equals(accessHost.getServerId(), node.getServerId())) {
                throw new IllegalArgumentException("节点接入 host 必须属于当前主服务器");
            }
        }

        List<Long> excludedNodeIds = groupNodes == null ? List.of() : groupNodes.stream()
                .map(Node::getId)
                .filter(Objects::nonNull)
                .toList();
        if (node.getServerId() != null && node.getPort() != null
                && !isNodePortsAvailable(node.getServerId(), node.getPort(), node.getId(), excludedNodeIds)) {
            throw new IllegalArgumentException("Port " + node.getPort() + " is already in use on the server");
        }
    }

    private void normalizeAndValidateNodeProtocol(Node node) {
        String coreType = node.getCoreType();
        if (coreType == null || coreType.trim().isEmpty()) {
            throw new IllegalArgumentException("Core type cannot be empty");
        }

        CoreType parsedCoreType = CoreType.fromValue(coreType);
        if (parsedCoreType == null || parsedCoreType == CoreType.HYSTERIA2) {
            throw new IllegalArgumentException("Unsupported core type: " + coreType);
        }
        node.setCoreType(parsedCoreType.getValue());

        if (node.getType() == null) {
            throw new IllegalArgumentException("Node type cannot be empty");
        }

        if (node.getProtocol() == null || node.getProtocol().trim().isEmpty()) {
            throw new IllegalArgumentException("Protocol cannot be empty");
        }

        if (!ProtocolType.isSupported(node.getProtocol(), node.getType(), node.getCoreType())) {
            String supportedProtocols = ProtocolType.getSupportedProtocols(node.getType(), node.getCoreType()).stream()
                    .map(ProtocolType::getLabel)
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException("Protocol " + node.getProtocol() + " is not supported for node type "
                    + node.getType() + " and core type " + node.getCoreType() + ". Supported protocols: " + supportedProtocols);
        }
    }

    @Transactional
    public Node saveNode(Node node, String nodeGroup) {
        if (node.getDeployed() == null) {
            node.setDeployed(0);
        }

        normalizeAndValidateNodeProtocol(node);
        node.setNodeGroup(nodeGroupService.normalizeNodeGroup(nodeGroup));
        List<Node> groupNodes = nodeGroupService.validateNodeGroup(node);
        nodeGroupService.alignNodeToGroupConfiguration(node, groupNodes);
        validateNodeServersAndPorts(node, groupNodes);

        nodeRepository.persist(node);
        nodeGroupService.markGroupNodesUndeployed(node.getNodeGroup(), node.getId());
        return node;
    }

    @Transactional
    public Node updateNode(Node node, String nodeGroup, Set<Tag> tagSet) {
        Node existingNode = nodeRepository.findById(node.getId());
        if (existingNode == null) {
            throw new EntityNotFoundException("Node not found");
        }
        String previousNodeGroup = nodeGroupService.normalizeNodeGroup(existingNode.getNodeGroup());

        if (node.getCoreType() != null
                && existingNode.getCoreType() != null
                && !node.getCoreType().equalsIgnoreCase(existingNode.getCoreType())) {
            throw new IllegalArgumentException("节点不允许通过编辑修改内核类型，请使用切换内核功能");
        }

        normalizeAndValidateNodeProtocol(node);
        node.setNodeGroup(nodeGroupService.normalizeNodeGroup(nodeGroup));
        List<Node> groupNodes = nodeGroupService.validateNodeGroup(node);
        nodeGroupService.alignNodeToGroupConfiguration(node, groupNodes);
        validateNodeServersAndPorts(node, groupNodes);
        boolean hasSubstantialChanges = hasSubstantialChanges(existingNode, node, tagSet);

        copyNonNullProperties(node, existingNode);
        existingNode.setOutId(node.getOutId());

        if (hasSubstantialChanges) {
            existingNode.setDeployed(0);
        }

        if (existingNode.getServerId() != null) {
            Server server = serverRepository.findById(existingNode.getServerId());
            existingNode.setServer(server);
        }

        existingNode.setAccessHost(existingNode.getAccessHostId() != null
                ? serverHostService.getHostById(existingNode.getAccessHostId())
                : null);

        if (existingNode.getOutId() != null) {
            Node outNode = nodeRepository.findById(existingNode.getOutId());
            existingNode.setOutNode(outNode);
        } else {
            existingNode.setOutNode(null);
        }

        nodeGroupService.markAffectedGroupNodesUndeployed(previousNodeGroup, existingNode.getNodeGroup(), existingNode.getId());
        return existingNode;
    }

    private boolean hasSubstantialChanges(Node oldNode, Node newNode, Set<Tag> newTagSet) {
        if (newNode.getPort() != null && !newNode.getPort().equals(oldNode.getPort())) {
            return true;
        }

        if (newNode.getType() != null && !newNode.getType().equals(oldNode.getType())) {
            return true;
        }

        if (!newNode.getCoreType().equals(oldNode.getCoreType())) {
            return true;
        }

        if (newNode.getServerId() != null && !newNode.getServerId().equals(oldNode.getServerId())) {
            return true;
        }

        if (!Objects.equals(newNode.getAccessHostId(), oldNode.getAccessHostId())) {
            return true;
        }

        if (newNode.getProtocol() != null && !newNode.getProtocol().equals(oldNode.getProtocol())) {
            return true;
        }

        if (newNode.getInbound() != null && !newNode.getInbound().equals(oldNode.getInbound().replaceAll(" ", ""))) {
            return true;
        }
        if (newNode.getRule() != null && !newNode.getRule().equals(oldNode.getRule())) {
            return true;
        }

        if (newNode.getOutId() == null && oldNode.getOutId() != null) {
            return true;
        }
        if (newNode.getOutId() != null && !newNode.getOutId().equals(oldNode.getOutId())) {
            return true;
        }

        if (newNode.getLevel() != null && !newNode.getLevel().equals(oldNode.getLevel())) {
            return true;
        }

        if (!Objects.equals(nodeGroupService.normalizeNodeGroup(newNode.getNodeGroup()),
                nodeGroupService.normalizeNodeGroup(oldNode.getNodeGroup()))) {
            return true;
        }

        if (newNode.getTags() != null && !newTagSet.equals(oldNode.getTags())) {
            return true;
        }

        return newNode.getDisabled() != null && !newNode.getDisabled().equals(oldNode.getDisabled());
    }

    private void copyNonNullProperties(Node src, Node target) {
        if (src.getName() != null) target.setName(src.getName());
        if (src.getNo() != null) target.setNo(src.getNo());
        if (src.getRemark() != null) target.setRemark(src.getRemark());
        if (src.getServerId() != null) target.setServerId(src.getServerId());
        target.setAccessHostId(src.getAccessHostId());
        if (src.getType() != null) target.setType(src.getType());
        if (src.getPort() != null) target.setPort(src.getPort());
        if (src.getProtocol() != null) target.setProtocol(src.getProtocol());
        if (src.getCoreType() != null) target.setCoreType(src.getCoreType());
        if (src.getInbound() != null) target.setInbound(src.getInbound());
        if (src.getRule() != null) target.setRule(src.getRule());
        if (src.getLevel() != null) target.setLevel(src.getLevel());
        target.setNodeGroup(src.getNodeGroup());
        if (src.getTags() != null) target.setTags(src.getTags());
        if (src.getDisabled() != null) target.setDisabled(src.getDisabled());
        if (src.getDeployed() != null) target.setDeployed(src.getDeployed());
        target.setOutId(src.getOutId());
    }

    private Node loadNode(Long nodeId, String label) {
        Node node = nodeRepository.findById(nodeId);
        if (node == null) {
            throw new EntityNotFoundException(label + " with ID " + nodeId + " not found");
        }
        return node;
    }

    @Transactional
    public void deleteNode(Long id) {
        nodeRepository.deleteById(id);
    }

    @Transactional
    public Node toggleNodeStatus(Long id, boolean disabled) {
        Node node = nodeRepository.findById(id);
        if (node != null) {
            node.setDisabled(disabled ? 1 : 0);
            node.setDeployed(0);
            return node;
        }
        return null;
    }

    public List<Map<String, Object>> getNodeTypeOptions() {
        return Arrays.stream(NodeType.values())
                .map(type -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("value", type.getValue());
                    option.put("label", type.getDescription());
                    return option;
                })
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getProtocolTypeOptions() {
        return Arrays.stream(ProtocolType.values())
                .map(type -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("value", type.getValue());
                    option.put("label", type.getLabel());
                    option.put("type", type.getType());
                    option.put("coreTypes", type.getCoreTypes());
                    return option;
                })
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getNodeCoreTypeOptions() {
        return Arrays.stream(CoreType.values())
                .filter(type -> type == CoreType.XRAY || type == CoreType.SING_BOX)
                .map(type -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("value", type.getValue());
                    option.put("label", type.getName());
                    return option;
                })
                .collect(Collectors.toList());
    }

    public Integer getAvailablePort(Long serverId) {
        List<Node> nodes = nodeRepository.findByServerId(serverId);

        Set<Integer> usedPorts = nodes.stream()
                .map(Node::getPort)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        int port = 10000;
        while (usedPorts.contains(port)) {
            port++;
        }

        return port;
    }

    public DefaultConfigDto<Map<String, Object>> generateDefaultInbound(String protocol, Long serverId, Long accessHostId, String coreType) {
        String normalizedCoreType = (coreType == null || coreType.trim().isEmpty()) ? CoreType.XRAY.getValue() : coreType;
        DefaultInboundStrategy strategy = strategyRegistry.getStrategy(normalizedCoreType);
        return strategy.generateDefaultInbound(protocol, serverId, accessHostId);
    }
}
