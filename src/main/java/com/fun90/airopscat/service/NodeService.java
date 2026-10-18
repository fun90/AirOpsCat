package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.DefaultConfigDto;
import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.convert.NodeConverter;
import com.fun90.airopscat.model.dto.NodeServerRequest;
import com.fun90.airopscat.util.TimeWindow;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.Tag;
import com.fun90.airopscat.model.enums.CoreType;
import com.fun90.airopscat.model.enums.NodeBatchTagUpdateMode;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.model.enums.ProtocolType;
import com.fun90.airopscat.repository.DomainDnsRecordRepository;
import com.fun90.airopscat.repository.NodeDeploymentHistoryRepository;
import com.fun90.airopscat.repository.NodeDeploymentRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.NodeServerRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.ServerHostRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.singbox.SingBoxDefaultInboundFactory;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.fun90.airopscat.util.JsonUtil;
import com.fun90.airopscat.util.NodeName;

@ApplicationScoped
public class NodeService {
    @Inject
    NodeRepository nodeRepository;

    @Inject
    NodeDeploymentRepository nodeDeploymentRepository;

    @Inject
    NodeDeploymentHistoryRepository nodeDeploymentHistoryRepository;

    @Inject
    ServerRepository serverRepository;

    @Inject
    NodePlacementSyncService placementSyncService;

    @Inject
    ServerDomainService serverDomainService;

    @Inject
    TagRepository tagRepository;

    @Inject
    SingBoxDefaultInboundFactory singBoxDefaultInboundFactory;

    @Inject
    NodeServerRepository nodeServerRepository;

    @Inject
    DomainDnsRecordRepository domainDnsRecordRepository;

    @Inject
    AccountOnlineIpService accountOnlineIpService;

    public io.quarkus.hibernate.orm.panache.PanacheQuery<Node> getNodePage(
            String search,
            String serverIds,
            Long nodeTagId,
            Integer type,
            String coreType,
            String protocol,
            Boolean disabled,
            Boolean deployed,
            Integer deploymentStatus,
            String sortBy,
            String sortOrder
    ) {
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (search != null && !search.trim().isEmpty()) {
            appendSearchConditions(query, params, search);
        }

        if (serverIds != null && !serverIds.trim().isEmpty()) {
            List<Long> idList = Arrays.stream(serverIds.split(","))
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
            if (!idList.isEmpty()) {
                query.append(" and id in (select ns.nodeId from NodeServer ns where ns.serverId in :serverIds)");
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

        NodeDeploymentStatus status = deploymentStatus != null
                ? NodeDeploymentStatus.fromValue(deploymentStatus)
                : deployed == null ? null : (deployed ? NodeDeploymentStatus.DEPLOYED : NodeDeploymentStatus.PENDING_DEPLOY);
        if (status != null) {
            appendDeploymentStatusCondition(query, params, status);
        }

        Sort sort = buildSort(sortBy, sortOrder);
        return nodeRepository.find(query.toString(), sort, params);
    }

    // 与 NodeServerRepository.aggregateStatus 的汇总规则保持一致
    private void appendDeploymentStatusCondition(StringBuilder query, Map<String, Object> params, NodeDeploymentStatus status) {
        if (status != NodeDeploymentStatus.PENDING_DELETE) {
            params.put("statusDeployed", NodeDeploymentStatus.DEPLOYED.getValue());
        }
        if (status != NodeDeploymentStatus.DEPLOYED) {
            params.put("statusPendingDelete", NodeDeploymentStatus.PENDING_DELETE.getValue());
        }
        String notDeployed = " in (select ns.nodeId from NodeServer ns where ns.deployed <> :statusDeployed)";
        String notPendingDelete = " in (select ns.nodeId from NodeServer ns where ns.deployed <> :statusPendingDelete)";
        switch (status) {
            case DEPLOYED -> query.append(" and id not").append(notDeployed);
            case PENDING_DELETE -> query.append(" and id not").append(notPendingDelete);
            default -> query.append(" and id").append(notDeployed).append(" and id").append(notPendingDelete);
        }
    }

    private void appendSearchConditions(StringBuilder query, Map<String, Object> params, String search) {
        String keyword = normalizeSearchKeyword(search);
        if (keyword == null) {
            return;
        }

        List<String> searchConditions = new ArrayList<>();
        String prefix = keyword + "%";
        String contains = "%" + keyword + "%";

        searchConditions.add("name = :searchExact");
        searchConditions.add("name like :searchPrefix");
        searchConditions.add("remark like :searchContains");

        List<Long> matchedServerIds = serverRepository.findIdsByKeyword(keyword, 200);
        if (!matchedServerIds.isEmpty()) {
            searchConditions.add("id in (select ns.nodeId from NodeServer ns where ns.serverId in :matchedServerIds)");
            params.put("matchedServerIds", matchedServerIds);
        }

        List<Long> matchedAccessHostIds = domainDnsRecordRepository.findIdsByFullNameKeyword(keyword, 200);
        if (!matchedAccessHostIds.isEmpty()) {
            searchConditions.add("id in (select ns.nodeId from NodeServer ns where ns.accessHostId in :matchedAccessHostIds)");
            params.put("matchedAccessHostIds", matchedAccessHostIds);
        }

        query.append(" and (").append(String.join(" or ", searchConditions)).append(")");
        params.put("searchExact", keyword);
        params.put("searchPrefix", prefix);
        params.put("searchContains", contains);
    }

    private String normalizeSearchKeyword(String search) {
        if (search == null) {
            return null;
        }
        String keyword = search.trim();
        return keyword.isEmpty() ? null : keyword.toLowerCase(Locale.ROOT);
    }

    private List<Long> mergeIds(List<Long> first, List<Long> second) {
        LinkedHashSet<Long> merged = new LinkedHashSet<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return new ArrayList<>(merged);
    }

    private Sort buildSort(String sortBy, String sortOrder) {
        boolean ascending = "asc".equalsIgnoreCase(sortOrder);

        if (sortBy == null || sortBy.trim().isEmpty()) {
            return Sort.by("createTime").descending();
        }

        return switch (sortBy) {
            case "name" -> ascending ? Sort.by("name").ascending() : Sort.by("name").descending();
            case "outbound" -> ascending ? Sort.by("outId", "name").ascending() : Sort.by("outId", "name").descending();
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
        return toNodeDtos(List.of(node)).getFirst();
    }

    public List<NodeDto> toNodeDtos(List<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        Set<Long> nodeIds = new LinkedHashSet<>();
        for (Node node : nodes) {
            nodeIds.add(node.getId());
            if (node.getOutId() != null) {
                nodeIds.add(node.getOutId());
            }
        }
        Map<Long, List<NodeServer>> placementsByNodeId = nodeServerRepository.groupByNodeIds(new ArrayList<>(nodeIds));
        return nodes.stream()
                .map(node -> NodeConverter.toDto(node,
                        placementsByNodeId.getOrDefault(node.getId(), List.of()),
                        node.getOutId() == null ? List.of() : placementsByNodeId.getOrDefault(node.getOutId(), List.of())))
                .toList();
    }

    public void fillOnlineConnectionCounts(List<NodeDto> nodeDtos) {
        if (nodeDtos == null || nodeDtos.isEmpty()) {
            return;
        }
        List<Long> nodeIds = nodeDtos.stream()
                .map(NodeDto::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Long> countByNodeId = accountOnlineIpService.countOnlineRecordsByNodeIds(nodeIds);
        nodeDtos.forEach(dto -> dto.setOnlineConnectionCount(
                countByNodeId.getOrDefault(dto.getId(), 0L).intValue()));
    }

    public List<Node> getNodeByType(NodeType nodeType) {
        return nodeRepository.findByType(nodeType.getValue());
    }

    public List<NodeServer> getPlacements(Long nodeId) {
        return nodeServerRepository.findByNodeId(nodeId);
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

    public List<Server> findMatchingServers(String region, Integer type) {
        Node probe = new Node();
        probe.setRegion(region);
        probe.setType(type);
        return placementSyncService.findCandidateServers(probe);
    }

    public boolean isNodePortsAvailable(Long serverId, Integer port, Long nodeId) {
        return !nodeServerRepository.existsPortConflict(serverId, port, nodeId);
    }

    private void validateNode(Node node) {
        if (node.getName() == null || node.getName().isBlank()) {
            throw new IllegalArgumentException("节点名称不能为空");
        }
        if (node.getName().codePointCount(0, node.getName().length()) > 257) {
            throw new IllegalArgumentException("节点名称不能超过 257 个字符");
        }
        if (node.getRegion() == null || node.getRegion().isBlank()) {
            throw new IllegalArgumentException("节点地区不能为空，部署位按地区和类型自动关联服务器");
        }
    }

    // 端口变化时，已有部署位所在服务器上不能冲突；新部署位的端口冲突由同步时检查
    private void validatePlacementPorts(Node node, List<NodeServer> placements) {
        if (node.getPort() == null) {
            return;
        }
        for (NodeServer placement : placements) {
            if (!NodeDeploymentStatus.isPendingDelete(placement.getDeployed())
                    && !isNodePortsAvailable(placement.getServerId(), node.getPort(), node.getId())) {
                throw new IllegalArgumentException("Port " + node.getPort() + " is already in use on server " + placement.getServerId());
            }
        }
    }

    private void normalizeAndValidateNodeProtocol(Node node) {
        String coreType = node.getCoreType();
        if (coreType == null || coreType.trim().isEmpty()) {
            coreType = CoreType.SING_BOX.getValue();
        }

        CoreType parsedCoreType = CoreType.fromValue(coreType);
        if (parsedCoreType == null) {
            throw new IllegalArgumentException("当前仅支持 sing-box 内核");
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
                    + node.getType() + ". Supported protocols: " + supportedProtocols);
        }
    }

    @Transactional
    public Node saveNode(Node node, List<NodeServerRequest> placementRequests) {
        node.setName(NodeName.withNumber(node.getName()));
        normalizeAndValidateNodeProtocol(node);
        validateNode(node);

        nodeRepository.persist(node);
        placementSyncService.syncNode(node, true);
        applyPlacementRequests(node, placementRequests);
        return node;
    }

    /**
     * @param placementRequests 部署位的 priority、exposed、域名；为 null 时保留现有部署位属性。部署位本身由地区和类型自动同步
     */
    @Transactional
    public Node updateNode(Node node, List<NodeServerRequest> placementRequests, Set<Tag> tagSet, boolean tagsUpdated) {
        Node existingNode = nodeRepository.findById(node.getId());
        if (existingNode == null) {
            throw new EntityNotFoundException("Node not found");
        }

        if (node.getCoreType() != null
                && existingNode.getCoreType() != null
                && !node.getCoreType().equalsIgnoreCase(existingNode.getCoreType())) {
            throw new IllegalArgumentException("当前系统仅支持 sing-box 内核，内核类型不可修改");
        }

        List<NodeServer> existingPlacements = nodeServerRepository.findByNodeId(existingNode.getId());

        node.setName(NodeName.withNumber(node.getName() == null ? existingNode.getName() : node.getName()));
        normalizeAndValidateNodeProtocol(node);
        boolean hasSubstantialChanges = hasSubstantialChanges(existingNode, node, tagSet, tagsUpdated);
        // 标签只影响本节点用户，不影响引用它作出站的代理节点
        boolean outboundConfigChanged = hasSubstantialChanges(existingNode, node, null, false);
        Optional<NodeServer> previousEffective = NodeServerRepository.resolveEffective(existingPlacements)
                .map(this::copyPlacementKey);

        copyNonNullProperties(node, existingNode);
        existingNode.setOutId(node.getOutId());
        validateNode(existingNode);
        validatePlacementPorts(existingNode, existingPlacements);

        placementSyncService.syncNode(existingNode, true);
        if (placementRequests != null) {
            applyPlacementRequests(existingNode, placementRequests);
        }
        if (hasSubstantialChanges) {
            markNodePendingDeploy(existingNode.getId());
        }
        markReferencingNodesIfChanged(existingNode, previousEffective, outboundConfigChanged);

        if (existingNode.getOutId() != null) {
            Node outNode = nodeRepository.findById(existingNode.getOutId());
            existingNode.setOutNode(outNode);
        } else {
            existingNode.setOutNode(null);
        }
        return existingNode;
    }

    // 按服务器匹配已有部署位，更新 priority、exposed 和域名；请求中不在部署位里的服务器忽略
    private void applyPlacementRequests(Node node, List<NodeServerRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        Map<Long, NodeServer> placementByServerId = nodeServerRepository.findByNodeId(node.getId()).stream()
                .filter(placement -> !NodeDeploymentStatus.isPendingDelete(placement.getDeployed()))
                .collect(Collectors.toMap(NodeServer::getServerId, placement -> placement, (left, right) -> left));
        for (NodeServerRequest request : requests) {
            if (request == null) {
                continue;
            }
            NodeServer placement = placementByServerId.get(request.getServerId());
            if (placement == null) {
                continue;
            }
            if (request.getPriority() != null) {
                placement.setPriority(request.getPriority());
            }
            if (request.getExposed() != null) {
                placement.setExposed(request.getExposed() == 0 ? 0 : 1);
            }
            applyCheapWindow(placement, request);
            applyPlacementDomain(placement, request);
        }
    }

    // 请求始终带完整的便宜时段，空值即清除
    private void applyCheapWindow(NodeServer placement, NodeServerRequest request) {
        String start = request.getCheapStart() == null || request.getCheapStart().isBlank() ? null : request.getCheapStart().trim();
        String end = request.getCheapEnd() == null || request.getCheapEnd().isBlank() ? null : request.getCheapEnd().trim();
        if (start == null && end == null) {
            placement.setCheapStart(null);
            placement.setCheapEnd(null);
            return;
        }
        if (!TimeWindow.isHhMm(start) || !TimeWindow.isHhMm(end) || start.equals(end)) {
            throw new IllegalArgumentException("便宜时段起止需为不同的 HH:mm");
        }
        placement.setCheapStart(start);
        placement.setCheapEnd(end);
    }

    private void applyPlacementDomain(NodeServer placement, NodeServerRequest request) {
        Long previousRecordId = placement.getAccessHostId();
        Server server = serverRepository.findById(placement.getServerId());
        Long recordId = null;
        if (request.getNewDomainId() != null) {
            recordId = serverDomainService.createARecord(request.getNewDomainId(), request.getNewSubdomain(), server).getId();
        } else if (request.getAccessHostId() != null) {
            recordId = serverDomainService.bindExisting(request.getAccessHostId(), server, placement.getId(), null).getId();
        }
        if (Objects.equals(previousRecordId, recordId)) {
            return;
        }
        placement.setAccessHostId(recordId);
        nodeServerRepository.flush();
        serverDomainService.releaseIfUnused(previousRecordId);
    }

    private NodeServer copyPlacementKey(NodeServer placement) {
        NodeServer copy = new NodeServer();
        copy.setServerId(placement.getServerId());
        return copy;
    }

    // 落地节点的配置或生效部署位变化后，引用它作出站的代理节点需重新部署
    private void markReferencingNodesIfChanged(Node node, Optional<NodeServer> previousEffective, boolean configChanged) {
        if (!Objects.equals(node.getType(), NodeType.LANDING.getValue())) {
            return;
        }
        Optional<NodeServer> currentEffective = nodeServerRepository.findEffectiveByNodeId(node.getId());
        boolean effectiveChanged = previousEffective.isPresent() != currentEffective.isPresent()
                || previousEffective.isPresent()
                && !Objects.equals(previousEffective.get().getServerId(), currentEffective.get().getServerId());
        if (configChanged || effectiveChanged) {
            markReferencingNodesPendingDeploy(node);
        }
    }

    void markReferencingNodesPendingDeploy(Node node) {
        if (Objects.equals(node.getType(), NodeType.LANDING.getValue())) {
            nodeRepository.findByOutId(node.getId()).forEach(proxy -> markNodePendingDeploy(proxy.getId()));
        }
    }

    @Transactional
    public Map<String, Integer> batchUpdateNodeTags(List<Long> nodeIds,
                                                    List<Long> tagIds,
                                                    NodeBatchTagUpdateMode mode) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            throw new IllegalArgumentException("请选择要调整标签的节点");
        }

        List<Long> uniqueNodeIds = nodeIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (uniqueNodeIds.isEmpty()) {
            throw new IllegalArgumentException("请选择要调整标签的节点");
        }

        List<Node> nodes = nodeRepository.findByIdIn(uniqueNodeIds);
        if (nodes.size() != uniqueNodeIds.size()) {
            throw new EntityNotFoundException("部分节点不存在，无法批量调整标签");
        }

        NodeBatchTagUpdateMode normalizedMode = mode == null ? NodeBatchTagUpdateMode.REPLACE : mode;
        List<Long> normalizedTagIds = tagIds == null
                ? List.of()
                : tagIds.stream().filter(Objects::nonNull).distinct().toList();
        List<Tag> resolvedTags = normalizedTagIds.isEmpty()
                ? List.of()
                : tagRepository.list("id in ?1", normalizedTagIds);
        if (resolvedTags.size() != normalizedTagIds.size()) {
            throw new EntityNotFoundException("部分标签不存在，无法批量调整");
        }

        Map<Long, Tag> tagMap = resolvedTags.stream()
                .collect(Collectors.toMap(Tag::getId, tag -> tag));
        LinkedHashSet<Long> selectedTagIds = new LinkedHashSet<>(normalizedTagIds);

        int updatedCount = 0;
        int unchangedCount = 0;
        for (Node node : nodes) {
            LinkedHashSet<Long> currentTagIds = extractNodeTagIds(node);
            LinkedHashSet<Long> targetTagIds = resolveTargetTagIds(currentTagIds, selectedTagIds, normalizedMode);

            if (currentTagIds.equals(targetTagIds)) {
                unchangedCount++;
                continue;
            }

            LinkedHashSet<Tag> targetTags = targetTagIds.stream()
                    .map(tagMap::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            node.setTags(targetTags);
            markNodePendingDeploy(node.getId());
            updatedCount++;
        }

        Map<String, Integer> result = new HashMap<>();
        result.put("updatedCount", updatedCount);
        result.put("unchangedCount", unchangedCount);
        return result;
    }

    private LinkedHashSet<Long> extractNodeTagIds(Node node) {
        if (node.getTags() == null) {
            return new LinkedHashSet<>();
        }
        return node.getTags().stream()
                .map(Tag::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private LinkedHashSet<Long> resolveTargetTagIds(LinkedHashSet<Long> currentTagIds,
                                                    LinkedHashSet<Long> selectedTagIds,
                                                    NodeBatchTagUpdateMode mode) {
        if (mode == NodeBatchTagUpdateMode.APPEND) {
            LinkedHashSet<Long> targetTagIds = new LinkedHashSet<>(currentTagIds);
            targetTagIds.addAll(selectedTagIds);
            return targetTagIds;
        }
        return new LinkedHashSet<>(selectedTagIds);
    }

    public boolean hasSubstantialChanges(Node oldNode, Node newNode, Set<Tag> newTagSet, boolean tagsUpdated) {
        return !buildSubstantialChangeSignature(oldNode, oldNode.getTags(), tagsUpdated).equals(
                buildSubstantialChangeSignature(newNode, newTagSet, tagsUpdated));
    }

    private String buildSubstantialChangeSignature(Node node, Set<Tag> tagSet, boolean includeTags) {
        StringJoiner joiner = new StringJoiner("|");
        joiner.add("port=" + normalizeValue(node.getPort()));
        joiner.add("type=" + normalizeValue(node.getType()));
        joiner.add("protocol=" + normalizeText(node.getProtocol()));
        joiner.add("inbound=" + normalizeJson(node.getInbound()));
        joiner.add("rule=" + normalizeJson(node.getRule()));
        joiner.add("outId=" + normalizeValue(node.getOutId()));
        // 编辑表单总会提交 level/disabled 的默认值，null 与 0 视为相同，避免仅改部署位属性也触发重新部署
        joiner.add("level=" + (node.getLevel() == null ? 0 : node.getLevel()));
        joiner.add("disabled=" + (node.getDisabled() == null ? 0 : node.getDisabled()));
        if (includeTags) {
            joiner.add("tags=" + normalizeTags(tagSet));
        }
        return md5Hex(joiner.toString());
    }

    private String normalizeValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return "";
        }
        Object parsedJson = JsonUtil.toObject(json, Object.class);
        return JsonUtil.toJsonString(canonicalizeJsonValue(parsedJson));
    }

    private Object canonicalizeJsonValue(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> normalizedMap = new TreeMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                normalizedMap.put(String.valueOf(entry.getKey()), canonicalizeJsonValue(entry.getValue()));
            }
            return normalizedMap;
        }

        if (value instanceof List<?> listValue) {
            return listValue.stream()
                    .map(this::canonicalizeJsonValue)
                    .toList();
        }

        return value;
    }

    private String normalizeTags(Set<Tag> tagSet) {
        if (tagSet == null || tagSet.isEmpty()) {
            return "";
        }
        return tagSet.stream()
                .map(Tag::getId)
                .filter(Objects::nonNull)
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    public String md5Hex(String source) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            byte[] digest = messageDigest.digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    private void copyNonNullProperties(Node src, Node target) {
        if (src.getName() != null) target.setName(src.getName());
        if (src.getRemark() != null) target.setRemark(src.getRemark());
        if (src.getType() != null) target.setType(src.getType());
        if (src.getRegion() != null) target.setRegion(src.getRegion());
        if (src.getPort() != null) target.setPort(src.getPort());
        if (src.getProtocol() != null) target.setProtocol(src.getProtocol());
        if (src.getCoreType() != null) target.setCoreType(src.getCoreType());
        if (src.getInbound() != null) target.setInbound(src.getInbound());
        if (src.getRule() != null) target.setRule(src.getRule());
        if (src.getLevel() != null) target.setLevel(src.getLevel());
        if (src.getTags() != null) target.setTags(src.getTags());
        if (src.getDisabled() != null) target.setDisabled(src.getDisabled());
        target.setOutId(src.getOutId());
    }

    /**
     * @return 删除后的节点部署状态汇总；节点已被物理删除时返回 null
     */
    @Transactional
    public Integer deleteNode(Long id) {
        Node node = nodeRepository.findById(id);
        if (node == null) {
            return null;
        }
        List<NodeServer> placements = nodeServerRepository.findByNodeId(id);
        placements.forEach(placementSyncService::removeOrMarkDeleted);
        List<NodeServer> remaining = nodeServerRepository.findByNodeId(id);
        if (remaining.isEmpty()) {
            physicallyDeleteNode(id);
            return null;
        }
        return NodeServerRepository.aggregateStatus(remaining);
    }

    /**
     * 取消删除：待删除的部署位恢复为待部署，由部署重建服务器上的完整配置。
     *
     * @return 节点不存在时返回 false
     */
    @Transactional
    public boolean cancelDeleteNode(Long id) {
        Node node = nodeRepository.findById(id);
        if (node == null) {
            return false;
        }
        List<NodeServer> pending = nodeServerRepository.findByNodeId(id).stream()
                .filter(placement -> NodeDeploymentStatus.isPendingDelete(placement.getDeployed()))
                .toList();
        if (pending.isEmpty()) {
            throw new IllegalArgumentException("节点不是待删除状态");
        }
        for (NodeServer placement : pending) {
            if (nodeServerRepository.existsPortConflict(placement.getServerId(), node.getPort(), id)) {
                throw new IllegalArgumentException("端口 " + node.getPort() + " 在服务器 " + placement.getServerId() + " 上已被占用，无法取消删除");
            }
            placement.setDeployed(NodeDeploymentStatus.PENDING_DEPLOY.getValue());
        }
        return true;
    }

    /**
     * 批量删除节点，同一事务，任一节点失败则全部回滚。
     *
     * @return deletedCount 已物理删除；pendingCount 已部署过，标记待删除，部署成功后从服务器移除
     */
    @Transactional
    public Map<String, Integer> batchDeleteNodes(List<Long> nodeIds) {
        List<Long> ids = nodeIds == null ? List.of() : nodeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("请先选择要删除的节点");
        }
        int deleted = 0;
        int pending = 0;
        for (Long id : ids) {
            if (nodeRepository.findById(id) == null) {
                throw new EntityNotFoundException("节点不存在: " + id);
            }
            if (deleteNode(id) == null) {
                deleted++;
            } else {
                pending++;
            }
        }
        return Map.of("deletedCount", deleted, "pendingCount", pending);
    }

    /**
     * 部署位入站已从服务器移除后删除部署位；最后一个部署位删除后删除节点。
     */
    @Transactional
    public void physicallyDeletePlacement(Long nodeServerId) {
        NodeServer placement = nodeServerRepository.findById(nodeServerId);
        if (placement == null) {
            return;
        }
        nodeDeploymentRepository.deleteByNodeServerId(nodeServerId);
        nodeDeploymentHistoryRepository.deleteByNodeServerId(nodeServerId);
        placementSyncService.delete(placement);
        nodeServerRepository.flush();
        if (nodeServerRepository.findByNodeId(placement.getNodeId()).isEmpty()) {
            physicallyDeleteNode(placement.getNodeId());
        }
    }

    @Transactional
    public void physicallyDeleteNode(Long id) {
        if (id == null) {
            return;
        }
        List<Long> accessHostIds = nodeServerRepository.findByNodeId(id).stream()
                .map(NodeServer::getAccessHostId).filter(Objects::nonNull).toList();
        tagRepository.deleteAllNodeTagsByNodeId(id);
        nodeDeploymentRepository.deleteByNodeId(id);
        nodeDeploymentHistoryRepository.deleteByNodeId(id);
        nodeServerRepository.getEntityManager().createNativeQuery("DELETE FROM account_node_subscription_placement "
                        + "WHERE node_server_id IN (SELECT id FROM node_server WHERE node_id = ?1)")
                .setParameter(1, id).executeUpdate();
        nodeServerRepository.delete("nodeId", id);
        nodeServerRepository.flush();
        accessHostIds.forEach(serverDomainService::releaseIfUnused);
        nodeRepository.deleteById(id);
    }

    @Transactional
    public Node toggleNodeStatus(Long id, boolean disabled) {
        Node node = nodeRepository.findById(id);
        if (node != null) {
            node.setDisabled(disabled ? 1 : 0);
            markNodePendingDeploy(id);
            markReferencingNodesPendingDeploy(node);
            return node;
        }
        return null;
    }

    public void markNodePendingDeploy(Long nodeId) {
        nodeServerRepository.findByNodeId(nodeId).forEach(this::markPendingDeploy);
    }

    public void markPendingDeploy(NodeServer placement) {
        if (placement == null || NodeDeploymentStatus.isPendingDelete(placement.getDeployed())) {
            return;
        }
        placement.setDeployed(NodeDeploymentStatus.PENDING_DEPLOY.getValue());
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
                    return option;
                })
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getNodeCoreTypeOptions() {
        return Arrays.stream(CoreType.values())
                .filter(type -> type == CoreType.SING_BOX)
                .map(type -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("value", type.getValue());
                    option.put("label", type.getName());
                    return option;
                })
                .collect(Collectors.toList());
    }

    public Integer getAvailablePort(List<Long> serverIds) {
        Set<Integer> usedPorts = serverIds.stream()
                .flatMap(serverId -> nodeServerRepository.findPortsByServerId(serverId).stream())
                .collect(Collectors.toSet());

        int port = 10000;
        while (usedPorts.contains(port)) {
            port++;
        }

        return port;
    }

    public DefaultConfigDto<Map<String, Object>> generateDefaultInbound(String protocol, Long serverId, Long accessHostId, String coreType) {
        String normalizedCoreType = (coreType == null || coreType.trim().isEmpty()) ? CoreType.SING_BOX.getValue() : coreType;
        if (!CoreType.SING_BOX.getValue().equalsIgnoreCase(normalizedCoreType)) {
            throw new IllegalArgumentException("当前仅支持 sing-box 内核");
        }
        return singBoxDefaultInboundFactory.generateDefaultInbound(protocol, serverId, accessHostId);
    }
}
