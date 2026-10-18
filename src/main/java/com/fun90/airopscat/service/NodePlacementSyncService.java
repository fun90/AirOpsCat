package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.repository.NodeDeploymentRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.NodeServerRepository;
import com.fun90.airopscat.repository.ServerRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 部署位按“节点地区 + 类型”自动同步：同地区、类型匹配、未禁用且非外部的服务器各一个部署位。
 * 同步只增删部署位，不改已有部署位的 priority、exposed、域名。
 */
@Slf4j
@ApplicationScoped
public class NodePlacementSyncService {

    @Inject
    NodeRepository nodeRepository;

    @Inject
    NodeServerRepository nodeServerRepository;

    @Inject
    ServerRepository serverRepository;

    @Inject
    NodeDeploymentRepository nodeDeploymentRepository;

    @Inject
    ServerDomainService serverDomainService;

    /**
     * 服务器能否承载该节点，不含“是否禁用”：禁用的服务器保留已有部署位，只是不再新生成。
     * 代理节点只用非外部的代理服务器；落地节点用落地服务器（含外部）和代理服务器（代理服务器可兼作落地）。
     */
    public static boolean matches(Node node, Server server) {
        if (node.getRegion() == null || node.getRegion().isBlank() || server.getRegion() == null) {
            return false;
        }
        if (!node.getRegion().trim().equals(server.getRegion().trim())) {
            return false;
        }
        boolean proxyNode = !Objects.equals(node.getType(), NodeType.LANDING.getValue());
        // 外部服务器不由本系统部署，只能承载落地节点（其配置在服务器外维护）
        if (proxyNode && Objects.equals(server.getExternal(), 1)) {
            return false;
        }
        boolean proxyServer = !Objects.equals(server.getServerType(), NodeType.LANDING.getValue());
        return !proxyNode || proxyServer;
    }

    /** 新生成部署位的候选服务器：匹配且未禁用 */
    public List<Server> findCandidateServers(Node node) {
        return serverRepository.listAll().stream()
                .filter(server -> matches(node, server))
                .filter(server -> !Objects.equals(server.getDisabled(), 1))
                .toList();
    }

    /**
     * 同步一个节点的部署位。节点地区为空时不动：避免未回填地区的存量节点被整体移除。
     *
     * @param strict true 时新部署位的端口在服务器上已被占用则抛出异常（节点保存），false 时跳过并记日志（服务器变更）
     */
    @Transactional
    public synchronized void syncNode(Node node, boolean strict) {
        if (node.getRegion() == null || node.getRegion().isBlank()) {
            return;
        }
        List<NodeServer> existing = nodeServerRepository.findByNodeId(node.getId());
        Map<Long, Server> servers = serverRepository.listAll().stream()
                .collect(Collectors.toMap(Server::getId, Function.identity()));

        for (NodeServer placement : existing) {
            Server server = servers.get(placement.getServerId());
            if (server != null && matches(node, server)) {
                if (NodeDeploymentStatus.isPendingDelete(placement.getDeployed())) {
                    // 重新匹配：恢复为待部署，由部署重建该服务器的完整配置
                    placement.setDeployed(NodeDeploymentStatus.PENDING_DEPLOY.getValue());
                }
            } else if (!NodeDeploymentStatus.isPendingDelete(placement.getDeployed())) {
                removeOrMarkDeleted(placement);
            }
        }

        Map<Long, NodeServer> existingByServerId = existing.stream()
                .collect(Collectors.toMap(NodeServer::getServerId, Function.identity(), (left, right) -> left));
        int nextPriority = existing.stream()
                .map(NodeServer::getPriority)
                .filter(Objects::nonNull)
                .max(Integer::compare)
                .orElse(0);
        for (Server server : findCandidateServers(node)) {
            if (existingByServerId.containsKey(server.getId())) {
                continue;
            }
            if (nodeServerRepository.existsPortConflict(server.getId(), node.getPort(), node.getId())) {
                String message = "端口 " + node.getPort() + " 在服务器 " + server.getName() + " 上已被占用";
                if (strict) {
                    throw new IllegalArgumentException(message);
                }
                log.warn("节点 {} 未关联服务器 {}：{}", node.getId(), server.getId(), message);
                continue;
            }
            NodeServer placement = new NodeServer();
            placement.setNodeId(node.getId());
            placement.setServerId(server.getId());
            placement.setExposed(1);
            placement.setPriority(++nextPriority);
            placement.setDeployed(NodeDeploymentStatus.PENDING_DEPLOY.getValue());
            nodeServerRepository.persist(placement);
        }
    }

    /** 服务器的地区、类型、状态变化后，同步全部节点 */
    @Transactional
    public void syncAll() {
        nodeRepository.listAll().forEach(node -> syncNode(node, false));
    }

    // 已部署过的部署位需先从服务器移除入站，未部署过的直接删除
    /** 部署位曾部署到服务器上：已部署、待删除，或留有部署记录 */
    public boolean isEverDeployed(NodeServer placement) {
        return NodeDeploymentStatus.isDeployed(placement.getDeployed())
                || NodeDeploymentStatus.isPendingDelete(placement.getDeployed())
                || nodeDeploymentRepository.findByNodeServerId(placement.getId()).isPresent();
    }

    @Transactional
    public void removeOrMarkDeleted(NodeServer placement) {
        if (isEverDeployed(placement)) {
            placement.setDeployed(NodeDeploymentStatus.PENDING_DELETE.getValue());
            return;
        }
        delete(placement);
    }

    @Transactional
    public void delete(NodeServer placement) {
        // 同时清理账户订阅定制中引用的该部署位
        nodeServerRepository.getEntityManager()
                .createNativeQuery("DELETE FROM account_node_subscription_placement WHERE node_server_id = ?1")
                .setParameter(1, placement.getId()).executeUpdate();
        Long accessHostId = placement.getAccessHostId();
        nodeServerRepository.delete(placement);
        nodeServerRepository.flush();
        serverDomainService.releaseIfUnused(accessHostId);
    }
}
