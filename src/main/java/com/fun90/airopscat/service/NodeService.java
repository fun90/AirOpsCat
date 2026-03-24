package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.DefaultConfigDto;
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

import java.util.*;
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

    public io.quarkus.hibernate.orm.panache.PanacheQuery<Node> getNodePage(
            String search,
            Long serverId,
            Long nodeTagId,
            Integer type,
            String coreType,
            String protocol,
            Boolean disabled,
            Boolean deployed
    ) {
        // Build query string
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        // Search in name, remark, or server properties
        if (search != null && !search.trim().isEmpty()) {
            String searchLike = "%" + search.toLowerCase() + "%";
            query.append(" and (lower(name) like :search or lower(remark) like :search")
                 .append(" or serverId in (select id from Server where lower(ip) like :search or lower(host) like :search"
                         + " or id in (select sh.serverId from ServerHost sh where lower(sh.host) like :search))")
                 .append(" or accessHostId in (select id from ServerHost where lower(host) like :search)");
            params.put("search", searchLike);

            List<Long> tagNodeIds = tagRepository.findNodeIdsByTagNameLike(searchLike);
            if (!tagNodeIds.isEmpty()) {
                query.append(" or id in :tagNodeIds");
                params.put("tagNodeIds", tagNodeIds);
            }
            query.append(")");
        }

        // Filter by serverId
        if (serverId != null) {
            query.append(" and serverId = :serverId");
            params.put("serverId", serverId);
        }

        if (nodeTagId != null) {
            query.append(" and id in (select n.id from Node n join n.tags t where t.id = :nodeTagId)");
            params.put("nodeTagId", nodeTagId);
        }

        // Filter by type
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

        // Filter by disabled status
        if (disabled != null) {
            query.append(" and disabled = :disabled");
            params.put("disabled", disabled ? 1 : 0);
        }

        if (deployed != null) {
            query.append(" and deployed = :deployed");
            params.put("deployed", deployed ? 1 : 0);
        }

        return nodeRepository.find(query.toString(), Sort.by("createTime").descending(), params);
    }

    public Node getNodeById(Long id) {
        return nodeRepository.findById(id);
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

    // 检查端口是否可用
    public boolean isPortAvailable(Long serverId, Integer port, Long nodeId) {
        if (nodeId == null) {
            return !nodeRepository.existsByServerIdAndPort(serverId, port);
        } else {
            return !nodeRepository.existsByServerIdAndPortAndIdNot(serverId, port, nodeId);
        }
    }

    // 检查备用服务器端口是否可用
    public boolean isBackupPortAvailable(Long backupServerId, Integer port, Long nodeId) {
        if (backupServerId == null) {
            return true; // 如果没有备用服务器，则认为可用
        }

        if (nodeId == null) {
            // 检查是否有其他节点在该备用服务器上使用了相同端口
            return !nodeRepository.existsByBackupServerIdAndPort(backupServerId, port);
        } else {
            // 检查是否有其他节点（除了当前节点）在该备用服务器上使用了相同端口
            return !nodeRepository.existsByBackupServerIdAndPortAndIdNot(backupServerId, port, nodeId);
        }
    }

    // 综合检查节点端口可用性（包括主服务器和备用服务器）- 使用单一SQL查询
    public boolean isNodePortsAvailable(Long serverId, Long backupServerId, Integer port, Long nodeId) {
        // 如果主服务器和备用服务器是同一个，直接返回false
        if (backupServerId != null && serverId.equals(backupServerId)) {
            return false;
        }

        // 使用单一SQL查询检查端口冲突
        return !nodeRepository.existsPortConflict(serverId, backupServerId, port, nodeId);
    }

    // 验证节点的服务器和端口
    private void validateNodeServersAndPorts(Node node) {
        // 确保名称+编号唯一
        if (nodeRepository.existsByNameAndNoAndIdNot(node.getName(), node.getNo(), node.getId())) {
            throw new IllegalArgumentException("名称与编号重复：" + node.getName() + " " + node.getNo());
        }

        // 确保主服务器存在
        if (node.getServerId() != null && serverRepository.findById(node.getServerId()) == null) {
            throw new EntityNotFoundException("Server with ID " + node.getServerId() + " not found");
        }

        // 确保备用服务器存在
        if (node.getBackupServerId() != null && serverRepository.findById(node.getBackupServerId()) == null) {
            throw new EntityNotFoundException("Backup server with ID " + node.getBackupServerId() + " not found");
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

        // 检查端口是否已被使用（包括主服务器和备用服务器）
        if (node.getServerId() != null && node.getPort() != null) {
            if (!isNodePortsAvailable(node.getServerId(), node.getBackupServerId(), node.getPort(), node.getId())) {
                if (node.getBackupServerId() != null && node.getServerId().equals(node.getBackupServerId())) {
                    throw new IllegalArgumentException("Main server and backup server cannot be the same");
                } else if (!isPortAvailable(node.getServerId(), node.getPort(), node.getId())) {
                    throw new IllegalArgumentException("Port " + node.getPort() + " is already in use on the main server");
                } else if (!isBackupPortAvailable(node.getBackupServerId(), node.getPort(), node.getId())) {
                    throw new IllegalArgumentException("Port " + node.getPort() + " is already in use on the backup server");
                }
            }
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
    public Node saveNode(Node node) {
        // 设置默认值（如果未提供）
        if (node.getDeployed() == null) {
            node.setDeployed(0);
        }

        // 验证服务器和端口
        validateNodeServersAndPorts(node);
        normalizeAndValidateNodeProtocol(node);

        nodeRepository.persist(node);
        return node;
    }

    @Transactional
    public Node updateNode(Node node, Set<Tag> tagSet) {
        Node existingNode = nodeRepository.findById(node.getId());
        if (existingNode == null) {
            throw new EntityNotFoundException("Node not found");
        }

        if (node.getCoreType() != null
                && existingNode.getCoreType() != null
                && !node.getCoreType().equalsIgnoreCase(existingNode.getCoreType())) {
            throw new IllegalArgumentException("节点不允许通过编辑修改内核类型，请使用切换内核功能");
        }

        // 验证服务器和端口
        validateNodeServersAndPorts(node);
        normalizeAndValidateNodeProtocol(node);

        // 检查节点是否有实质性变更
        boolean hasSubstantialChanges = hasSubstantialChanges(existingNode, node, tagSet);

        // 使用工具方法复制非null属性
        copyNonNullProperties(node, existingNode);

        // 特殊处理 outId 字段，确保 null 值也能被更新
        existingNode.setOutId(node.getOutId());

        // 如果有实质性变更，将状态设置为"未部署"
        if (hasSubstantialChanges) {
            existingNode.setDeployed(0); // 设置为"未部署"
        }

        // No need to call save/persist for updates in Panache
        // 手动加载关联的Server对象，避免lazy loading问题
        if (existingNode.getServerId() != null) {
            Server server = serverRepository.findById(existingNode.getServerId());
            existingNode.setServer(server);
        }

        // 手动加载关联的BackupServer对象，避免lazy loading问题
        if (existingNode.getBackupServerId() != null) {
            Server backupServer = serverRepository.findById(existingNode.getBackupServerId());
            existingNode.setBackupServer(backupServer);
        }

        existingNode.setAccessHost(existingNode.getAccessHostId() != null
                ? serverHostService.getHostById(existingNode.getAccessHostId())
                : null);

        // 手动加载关联的OutNode对象，避免lazy loading问题
        if (existingNode.getOutId() != null) {
            Node outNode = nodeRepository.findById(existingNode.getOutId());
            existingNode.setOutNode(outNode);
        }

        return existingNode;
    }

    /**
     * 检查节点是否有实质性变更（影响部署的变更）
     */
    private boolean hasSubstantialChanges(Node oldNode, Node newNode, Set<Tag> newTagSet) {
        // 检查端口变更
        if (newNode.getPort() != null && !newNode.getPort().equals(oldNode.getPort())) {
            return true;
        }

        // 检查类型变更
        if (newNode.getType() != null && !newNode.getType().equals(oldNode.getType())) {
            return true;
        }

        String newCoreType = newNode.getCoreType();
        String oldCoreType = oldNode.getCoreType();
        if (!newCoreType.equals(oldCoreType)) {
            return true;
        }

        // 检查服务器变更
        if (newNode.getServerId() != null && !newNode.getServerId().equals(oldNode.getServerId())) {
            return true;
        }

        if (!Objects.equals(newNode.getAccessHostId(), oldNode.getAccessHostId())) {
            return true;
        }

        // 检查备用服务器变更
        if (newNode.getBackupServerId() == null && oldNode.getBackupServerId() != null) {
            return true;
        }
        if (newNode.getBackupServerId() != null && !newNode.getBackupServerId().equals(oldNode.getBackupServerId())) {
            return true;
        }

        // 检查配置变更
        if (newNode.getInbound() != null && !newNode.getInbound().equals(oldNode.getInbound().replaceAll(" ", ""))) {
            return true;
        }
        if (newNode.getRule() != null && !newNode.getRule().equals(oldNode.getRule())) {
            return true;
        }

        // 特殊处理 outId 变更，包括从有值变为 null 的情况
        if (newNode.getOutId() == null && oldNode.getOutId() != null) {
            return true;
        }
        if (newNode.getOutId() != null && !newNode.getOutId().equals(oldNode.getOutId())) {
            return true;
        }

        // 如果级别变更（会影响访问权限）
        if (newNode.getLevel() != null && !newNode.getLevel().equals(oldNode.getLevel())) {
            return true;
        }

        // 检查是否存在tag变更
        if (newNode.getTags() != null && !newTagSet.equals(oldNode.getTags())) {
            return true;
        }

        // 其他可能影响部署的字段...
        return newNode.getDisabled() != null && !newNode.getDisabled().equals(oldNode.getDisabled());
    }

    // 工具方法：手动复制非null属性（替代Spring BeanUtils）
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
        if (src.getTags() != null) target.setTags(src.getTags());
        if (src.getDisabled() != null) target.setDisabled(src.getDisabled());
        if (src.getDeployed() != null) target.setDeployed(src.getDeployed());
        // 特殊处理：outId 可能为 null，需要显式设置
        target.setOutId(src.getOutId());
        // 特殊处理：backupServerId 可能为 null，需要显式设置
        target.setBackupServerId(src.getBackupServerId());
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
            // No need to call save/persist for updates in Panache
            return node;
        }
        return null;
    }

    // 获取节点类型选项
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

    // 获取协议类型选项
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

    // 获取可用端口
    public Integer getAvailablePort(Long serverId) {
        List<Node> nodes = nodeRepository.findByServerId(serverId);

        // 整理已使用的端口
        Set<Integer> usedPorts = nodes.stream()
                .map(Node::getPort)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 查找未使用的端口（从10000开始）
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
