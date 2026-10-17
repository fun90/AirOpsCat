package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class NodeServerRepository implements PanacheRepository<NodeServer> {

    public static final Comparator<NodeServer> PRIORITY_ORDER = Comparator
            .comparing((NodeServer placement) -> placement.getPriority() == null ? Integer.MAX_VALUE : placement.getPriority())
            .thenComparing(placement -> placement.getId() == null ? Long.MAX_VALUE : placement.getId());

    public List<NodeServer> findByNodeId(Long nodeId) {
        if (nodeId == null) {
            return List.of();
        }
        return find("nodeId = ?1 order by priority, id", nodeId).list();
    }

    public List<NodeServer> findByNodeIds(List<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return List.of();
        }
        return find("nodeId in ?1 order by priority, id", nodeIds).list();
    }

    public Map<Long, List<NodeServer>> groupByNodeIds(List<Long> nodeIds) {
        return findByNodeIds(nodeIds).stream()
                .collect(Collectors.groupingBy(NodeServer::getNodeId));
    }

    public List<NodeServer> findByServerId(Long serverId) {
        if (serverId == null) {
            return List.of();
        }
        return find("serverId = ?1 order by priority, id", serverId).list();
    }

    public List<NodeServer> findByServerIds(List<Long> serverIds) {
        if (serverIds == null || serverIds.isEmpty()) {
            return List.of();
        }
        return find("serverId in ?1 order by priority, id", serverIds).list();
    }

    public List<NodeServer> findByDeployedIn(List<Integer> statuses, List<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return find("deployed in ?1", statuses).list();
        }
        return find("deployed in ?1 and nodeId in ?2", statuses, nodeIds).list();
    }

    public long countByServerId(Long serverId) {
        return serverId == null ? 0 : count("serverId", serverId);
    }

    public boolean existsPortConflict(Long serverId, Integer port, Long excludeNodeId) {
        if (serverId == null || port == null) {
            return false;
        }
        return getEntityManager().createQuery("""
                        select count(ns.id) from NodeServer ns, Node n
                        where n.id = ns.nodeId and ns.serverId = :serverId and n.port = :port
                          and n.id <> :excludeNodeId
                        """, Long.class)
                .setParameter("serverId", serverId)
                .setParameter("port", port)
                .setParameter("excludeNodeId", excludeNodeId == null ? -1L : excludeNodeId)
                .getSingleResult() > 0;
    }

    public List<Integer> findPortsByServerId(Long serverId) {
        if (serverId == null) {
            return List.of();
        }
        return getEntityManager().createQuery("""
                        select distinct n.port from NodeServer ns, Node n
                        where n.id = ns.nodeId and ns.serverId = :serverId and n.port is not null
                        """, Integer.class)
                .setParameter("serverId", serverId)
                .getResultList();
    }

    public long clearAccessHostIds(List<Long> accessHostIds) {
        if (accessHostIds == null || accessHostIds.isEmpty()) {
            return 0;
        }
        return update("accessHostId = null where accessHostId in ?1", accessHostIds);
    }

    public int clearAccessHostIdsByServerId(Long serverId) {
        if (serverId == null) {
            return 0;
        }
        return getEntityManager().createNativeQuery("""
                        UPDATE node_server
                        SET access_host_id = NULL
                        WHERE access_host_id IN (
                            SELECT id FROM server_host WHERE server_id = ?1
                        )
                        """)
                .setParameter(1, serverId)
                .executeUpdate();
    }

    /**
     * 生效部署位：启用、未待删除、priority 最小。第二期改为优先取 active_node_server_id。
     */
    public static Optional<NodeServer> resolveEffective(List<NodeServer> placements) {
        if (placements == null) {
            return Optional.empty();
        }
        return placements.stream()
                .filter(NodeServer::enabledFlag)
                .filter(placement -> !NodeDeploymentStatus.isPendingDelete(placement.getDeployed()))
                .min(PRIORITY_ORDER);
    }

    public Optional<NodeServer> findEffectiveByNodeId(Long nodeId) {
        return resolveEffective(findByNodeId(nodeId));
    }

    /**
     * 节点部署状态汇总：全部待删除为待删除；任一未完成为待部署；否则已部署。
     */
    public static Integer aggregateStatus(List<NodeServer> placements) {
        if (placements == null || placements.isEmpty()) {
            return NodeDeploymentStatus.PENDING_DEPLOY.getValue();
        }
        if (placements.stream().allMatch(placement -> NodeDeploymentStatus.isPendingDelete(placement.getDeployed()))) {
            return NodeDeploymentStatus.PENDING_DELETE.getValue();
        }
        if (placements.stream().allMatch(placement -> NodeDeploymentStatus.isDeployed(placement.getDeployed()))) {
            return NodeDeploymentStatus.DEPLOYED.getValue();
        }
        return NodeDeploymentStatus.PENDING_DEPLOY.getValue();
    }
}
