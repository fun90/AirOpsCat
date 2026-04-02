package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.NodeDeploymentVersionDetailDto;
import com.fun90.airopscat.model.dto.NodeDeploymentVersionDto;
import com.fun90.airopscat.model.dto.NodeDeploymentVersionSnapshotDto;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeDeployment;
import com.fun90.airopscat.model.entity.NodeDeploymentHistory;
import com.fun90.airopscat.model.entity.Tag;
import com.fun90.airopscat.repository.NodeDeploymentHistoryRepository;
import com.fun90.airopscat.repository.NodeDeploymentRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.service.NodeService;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.TagService;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class NodeDeploymentVersionService {

    private static final int DEFAULT_HISTORY_RETENTION_DAYS = 90;
    private static final int DEFAULT_HISTORY_KEEP_LATEST_PER_NODE = 20;
    private static final int DEFAULT_HISTORY_CLEANUP_BATCH_SIZE = 500;

    private final NodeDeploymentRepository nodeDeploymentRepository;
    private final NodeDeploymentHistoryRepository nodeDeploymentHistoryRepository;
    private final NodeRepository nodeRepository;
    private final TagRepository tagRepository;
    private final TagService tagService;
    private final NodeService nodeService;
    private final SystemConfigService systemConfigService;

    @Transactional
    public void recordSuccessfulDeployments(List<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }

        for (Node node : nodes) {
            if (node == null || node.getId() == null) {
                continue;
            }
            Node currentNode = nodeRepository.findById(node.getId());
            if (currentNode == null) {
                continue;
            }
            upsertCurrentVersion(currentNode);
        }
    }

    public List<NodeDeploymentVersionDto> listVersions(Long nodeId) {
        ensureNodeExists(nodeId);

        List<NodeDeploymentVersionDto> versions = new ArrayList<>();
        nodeDeploymentRepository.findByNodeId(nodeId)
                .map(this::toCurrentVersionDto)
                .ifPresent(versions::add);
        nodeDeploymentHistoryRepository.findByNodeIdOrderByVersionDesc(nodeId).stream()
                .map(this::toHistoryVersionDto)
                .forEach(versions::add);
        return versions;
    }

    public NodeDeploymentVersionDetailDto getVersionDetail(Long nodeId, Integer version) {
        ensureNodeExists(nodeId);
        if (version == null || version < 1) {
            throw new IllegalArgumentException("版本号无效");
        }

        NodeDeployment current = nodeDeploymentRepository.findByNodeId(nodeId).orElse(null);
        if (current != null && version.equals(current.getVersion())) {
            return toCurrentDetailDto(current);
        }

        NodeDeploymentHistory history = nodeDeploymentHistoryRepository.findByNodeIdAndVersion(nodeId, version)
                .orElseThrow(() -> new EntityNotFoundException("部署版本不存在"));
        return toHistoryDetailDto(history);
    }

    @Transactional
    public Node restoreVersion(Long nodeId, Integer version) {
        ensureNodeExists(nodeId);
        NodeDeploymentVersionDetailDto detail = getVersionDetail(nodeId, version);
        NodeDeploymentVersionSnapshotDto snapshot = detail.getSnapshot();
        if (snapshot == null) {
            throw new IllegalArgumentException("部署版本快照为空，无法还原");
        }

        Node restoredNode = toNode(snapshot);
        Set<Tag> restoredTags = toTagSet(snapshot.getTagIds());
        Node updatedNode = nodeService.updateNode(restoredNode, snapshot.getNodeGroup(), restoredTags, true);
        tagService.updateNodeTags(nodeId, snapshot.getTagIds());
        return updatedNode;
    }

    @Transactional
    public int initializeFromLegacyDeployedNodes() {
        if (nodeDeploymentRepository.count() > 0) {
            return 0;
        }

        List<Node> deployedNodes = nodeRepository.findByDeployed(1);
        if (deployedNodes.isEmpty()) {
            return 0;
        }

        recordSuccessfulDeployments(deployedNodes);
        return deployedNodes.size();
    }

    @Transactional
    public long cleanupExpiredHistory() {
        int retentionDays = getHistoryRetentionDays();
        int keepLatestPerNode = getHistoryKeepLatestPerNode();
        int batchSize = getHistoryCleanupBatchSize();
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
        long totalDeleted = 0L;
        int rounds = 0;

        while (true) {
            int deleted = deleteExpiredHistoryBatch(cutoffTime, keepLatestPerNode, batchSize);
            if (deleted <= 0) {
                break;
            }
            totalDeleted += deleted;
            rounds++;
            if (deleted < batchSize) {
                break;
            }
        }

        logCleanupSummary(retentionDays, keepLatestPerNode, batchSize, cutoffTime, rounds, totalDeleted);
        return totalDeleted;
    }

    private void upsertCurrentVersion(Node node) {
        List<Tag> currentTags = tagRepository.findByNodeId(node.getId());
        NodeDeploymentVersionSnapshotDto snapshot = toSnapshotDto(node, currentTags);
        String snapshotJson = JsonUtil.toJsonString(snapshot);
        NodeDeployment currentDeployment = nodeDeploymentRepository.findByNodeId(node.getId()).orElse(null);
        LocalDateTime deployedAt = LocalDateTime.now();

        if (currentDeployment == null) {
            NodeDeployment deployment = new NodeDeployment();
            fillCurrentDeployment(deployment, node, snapshotJson, 1, deployedAt);
            nodeDeploymentRepository.persist(deployment);
            return;
        }

        NodeDeploymentVersionSnapshotDto previousSnapshot = JsonUtil.toObject(
                currentDeployment.getSnapshotJson(),
                NodeDeploymentVersionSnapshotDto.class
        );
        Node previousNode = toNode(previousSnapshot);
        previousNode.setTags(toTagSet(previousSnapshot.getTagIds()));
        boolean changed = nodeService.hasSubstantialChanges(previousNode, node, new LinkedHashSet<>(currentTags), true);
        if (!changed) {
            fillCurrentDeployment(currentDeployment, node, snapshotJson, currentDeployment.getVersion(), deployedAt);
            return;
        }

        archiveCurrentDeployment(currentDeployment);
        fillCurrentDeployment(currentDeployment, node, snapshotJson, currentDeployment.getVersion() + 1, deployedAt);
    }

    private void archiveCurrentDeployment(NodeDeployment currentDeployment) {
        NodeDeploymentHistory history = new NodeDeploymentHistory();
        history.setNodeId(currentDeployment.getNodeId());
        history.setVersion(currentDeployment.getVersion());
        history.setSnapshotHash(currentDeployment.getSnapshotHash());
        history.setSnapshotJson(currentDeployment.getSnapshotJson());
        history.setServerId(currentDeployment.getServerId());
        history.setNodeGroup(currentDeployment.getNodeGroup());
        history.setCoreType(currentDeployment.getCoreType());
        history.setProtocol(currentDeployment.getProtocol());
        history.setType(currentDeployment.getType());
        history.setPort(currentDeployment.getPort());
        history.setDisabled(currentDeployment.getDisabled());
        history.setDeployedAt(currentDeployment.getDeployedAt());
        history.setArchivedAt(LocalDateTime.now());
        nodeDeploymentHistoryRepository.persist(history);
    }

    int deleteExpiredHistoryBatch(LocalDateTime cutoffTime, int keepLatestPerNode, int batchSize) {
        return nodeDeploymentHistoryRepository.deleteExpiredHistoryBatch(cutoffTime, keepLatestPerNode, batchSize);
    }

    int getHistoryRetentionDays() {
        return Math.max(systemConfigService.getIntValue(
                "airopscat.node.deployment.history.retention-days", DEFAULT_HISTORY_RETENTION_DAYS), 1);
    }

    int getHistoryKeepLatestPerNode() {
        return Math.max(systemConfigService.getIntValue(
                "airopscat.node.deployment.history.keep-latest-per-node", DEFAULT_HISTORY_KEEP_LATEST_PER_NODE), 0);
    }

    int getHistoryCleanupBatchSize() {
        return Math.max(systemConfigService.getIntValue(
                "airopscat.node.deployment.history.cleanup.batch-size", DEFAULT_HISTORY_CLEANUP_BATCH_SIZE), 1);
    }

    void logCleanupSummary(int retentionDays,
                           int keepLatestPerNode,
                           int batchSize,
                           LocalDateTime cutoffTime,
                           int rounds,
                           long totalDeleted) {
        log.info("节点部署历史清理完成，保留天数: {}, 每节点保留版本数: {}, 截止时间: {}, 批大小: {}, 批次数: {}, 删除总数: {}",
                retentionDays, keepLatestPerNode, cutoffTime, batchSize, rounds, totalDeleted);
    }

    private void fillCurrentDeployment(NodeDeployment deployment,
                                       Node node,
                                       String snapshotJson,
                                       Integer version,
                                       LocalDateTime deployedAt) {
        deployment.setNodeId(node.getId());
        deployment.setVersion(version);
        deployment.setSnapshotHash(snapshotJson == null ? "" : nodeService.md5Hex(snapshotJson));
        deployment.setSnapshotJson(snapshotJson);
        deployment.setServerId(node.getServerId());
        deployment.setNodeGroup(node.getNodeGroup());
        deployment.setCoreType(node.getCoreType());
        deployment.setProtocol(node.getProtocol());
        deployment.setType(node.getType());
        deployment.setPort(node.getPort());
        deployment.setDisabled(node.getDisabled());
        deployment.setDeployedAt(deployedAt);
    }

    private NodeDeploymentVersionSnapshotDto toSnapshotDto(Node node, List<Tag> tags) {
        NodeDeploymentVersionSnapshotDto snapshot = new NodeDeploymentVersionSnapshotDto();
        snapshot.setNodeId(node.getId());
        snapshot.setServerId(node.getServerId());
        snapshot.setNodeGroup(node.getNodeGroup());
        snapshot.setAccessHostId(node.getAccessHostId());
        snapshot.setPort(node.getPort());
        snapshot.setProtocol(node.getProtocol());
        snapshot.setCoreType(node.getCoreType());
        snapshot.setType(node.getType());
        snapshot.setInbound(toJsonMap(node.getInbound()));
        snapshot.setOutId(node.getOutId());
        snapshot.setRule(toJsonMap(node.getRule()));
        snapshot.setLevel(node.getLevel());
        snapshot.setDisabled(node.getDisabled());
        snapshot.setName(node.getName());
        snapshot.setNo(node.getNo());
        snapshot.setRemark(node.getRemark());
        snapshot.setTagIds(tags.stream().map(Tag::getId).toList());
        return snapshot;
    }

    private Map<String, Object> toJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return JsonUtil.toObject(json, Map.class);
    }

    private Node toNode(NodeDeploymentVersionSnapshotDto snapshot) {
        Node node = new Node();
        node.setId(snapshot.getNodeId());
        node.setServerId(snapshot.getServerId());
        node.setNodeGroup(snapshot.getNodeGroup());
        node.setAccessHostId(snapshot.getAccessHostId());
        node.setPort(snapshot.getPort());
        node.setProtocol(snapshot.getProtocol());
        node.setCoreType(snapshot.getCoreType());
        node.setType(snapshot.getType());
        node.setInbound(JsonUtil.toJsonString(snapshot.getInbound()));
        node.setOutId(snapshot.getOutId());
        node.setRule(JsonUtil.toJsonString(snapshot.getRule()));
        node.setLevel(snapshot.getLevel());
        node.setDisabled(snapshot.getDisabled());
        node.setName(snapshot.getName());
        node.setNo(snapshot.getNo());
        node.setRemark(snapshot.getRemark());
        return node;
    }

    private Set<Tag> toTagSet(List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(tagRepository.list("id in ?1", tagIds));
    }

    private NodeDeploymentVersionDto toCurrentVersionDto(NodeDeployment deployment) {
        NodeDeploymentVersionDto dto = new NodeDeploymentVersionDto();
        dto.setVersion(deployment.getVersion());
        dto.setCurrent(true);
        dto.setServerId(deployment.getServerId());
        dto.setNodeGroup(deployment.getNodeGroup());
        dto.setCoreType(deployment.getCoreType());
        dto.setProtocol(deployment.getProtocol());
        dto.setType(deployment.getType());
        dto.setPort(deployment.getPort());
        dto.setDisabled(deployment.getDisabled());
        dto.setDeployedAt(deployment.getDeployedAt());
        return dto;
    }

    private NodeDeploymentVersionDto toHistoryVersionDto(NodeDeploymentHistory history) {
        NodeDeploymentVersionDto dto = new NodeDeploymentVersionDto();
        dto.setVersion(history.getVersion());
        dto.setCurrent(false);
        dto.setServerId(history.getServerId());
        dto.setNodeGroup(history.getNodeGroup());
        dto.setCoreType(history.getCoreType());
        dto.setProtocol(history.getProtocol());
        dto.setType(history.getType());
        dto.setPort(history.getPort());
        dto.setDisabled(history.getDisabled());
        dto.setDeployedAt(history.getDeployedAt());
        dto.setArchivedAt(history.getArchivedAt());
        return dto;
    }

    private NodeDeploymentVersionDetailDto toCurrentDetailDto(NodeDeployment deployment) {
        NodeDeploymentVersionDetailDto dto = new NodeDeploymentVersionDetailDto();
        dto.setVersion(deployment.getVersion());
        dto.setCurrent(true);
        dto.setDeployedAt(deployment.getDeployedAt());
        dto.setSnapshot(JsonUtil.toObject(deployment.getSnapshotJson(), NodeDeploymentVersionSnapshotDto.class));
        return dto;
    }

    private NodeDeploymentVersionDetailDto toHistoryDetailDto(NodeDeploymentHistory history) {
        NodeDeploymentVersionDetailDto dto = new NodeDeploymentVersionDetailDto();
        dto.setVersion(history.getVersion());
        dto.setCurrent(false);
        dto.setDeployedAt(history.getDeployedAt());
        dto.setArchivedAt(history.getArchivedAt());
        dto.setSnapshot(JsonUtil.toObject(history.getSnapshotJson(), NodeDeploymentVersionSnapshotDto.class));
        return dto;
    }

    private void ensureNodeExists(Long nodeId) {
        if (nodeRepository.findById(nodeId) == null) {
            throw new EntityNotFoundException("节点不存在");
        }
    }
}
