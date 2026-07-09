package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class NodeRepository implements PanacheRepository<Node> {

    public List<Node> findByServerId(Long serverId) {
        return find("serverId", serverId).list();
    }

    public List<Node> findOnlineTrackableByServerId(Long serverId) {
        if (serverId == null) {
            return List.of();
        }
        return find("serverId = ?1 and type = 0 and (disabled is null or disabled = 0) and deployed = ?2",
                serverId, NodeDeploymentStatus.DEPLOYED.getValue()).list();
    }

    public List<Node> findByServerIdIn(List<Long> serverIds) {
        if (serverIds == null || serverIds.isEmpty()) {
            return List.of();
        }
        return find("serverId in ?1", serverIds).list();
    }

    public List<Node> findByIdIn(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return find("id in ?1", ids).list();
    }

    public List<Node> findByNodeGroup(String nodeGroup) {
        if (nodeGroup == null || nodeGroup.isBlank()) {
            return List.of();
        }
        return find("nodeGroup", nodeGroup.trim()).list();
    }

    public List<Node> findByNodeGroupAndIdNot(String nodeGroup, Long excludeId) {
        if (nodeGroup == null || nodeGroup.isBlank()) {
            return List.of();
        }
        if (excludeId == null) {
            return findByNodeGroup(nodeGroup);
        }
        return find("nodeGroup = ?1 and id != ?2", nodeGroup.trim(), excludeId).list();
    }

    public List<Node> findByNodeGroupIn(List<String> nodeGroups) {
        if (nodeGroups == null || nodeGroups.isEmpty()) {
            return List.of();
        }
        return find("nodeGroup in ?1", nodeGroups).list();
    }

    public long countActiveByServerAssociation(Long serverId) {
        if (serverId == null) {
            return 0;
        }
        return count("serverId = ?1 and (disabled is null or disabled = 0) and deployed != ?2",
                serverId, NodeDeploymentStatus.PENDING_DELETE.getValue());
    }
    
    public List<Node> findByType(Integer type) {
        return find("type", type).list();
    }

    public List<Node> findByDeployed(Integer deployed) {
        return find("deployed", deployed).list();
    }

    public List<Node> findByDeployedIn(List<Integer> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return find("deployed in ?1", statuses).list();
    }

    public List<Node> findByDeployedAndIdIn(Integer deployed, List<Long> nodeIds) {
        return find("deployed = ?1 and id in ?2", deployed, nodeIds).list();
    }

    public List<Node> findByDeployedInAndIdIn(List<Integer> statuses, List<Long> nodeIds) {
        if (statuses == null || statuses.isEmpty() || nodeIds == null || nodeIds.isEmpty()) {
            return List.of();
        }
        return find("deployed in ?1 and id in ?2", statuses, nodeIds).list();
    }

    public long fillEmptyCoreType(String coreType) {
        return update("coreType = ?1 where coreType is null or trim(coreType) = ''", coreType);
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
                        UPDATE node
                        SET access_host_id = NULL
                        WHERE access_host_id IN (
                            SELECT id FROM server_host WHERE server_id = ?1
                        )
                        """)
                .setParameter(1, serverId)
                .executeUpdate();
    }

    public long countProxyNodes() {
        return count("type = 0");
    }
    
    public long countLandingNodes() {
        return count("type = 1");
    }
    
    public long countActiveNodes() {
        return count("disabled = 0");
    }
    
    public long countDisabledNodes() {
        return count("disabled = 1");
    }
    
    // 检查端口是否已被使用
    public boolean existsByServerIdAndPortAndIdNot(Long serverId, Integer port, Long id) {
        return count("serverId = ?1 and port = ?2 and id != ?3", serverId, port, id) > 0;
    }

    // 检查名称和编号是否已被使用
    public boolean existsByNameAndNoAndIdNot(String name, Integer no, Long id) {
        return count("name = ?1 and no = ?2 and id != ?3", name, no, id) > 0;
    }
    
    public boolean existsByServerIdAndPort(Long serverId, Integer port) {
        return count("serverId = ?1 and port = ?2", serverId, port) > 0;
    }
    
    public boolean existsByServerIdAndPortAndIdNotIn(Long serverId, Integer port, List<Long> excludedIds) {
        if (excludedIds == null || excludedIds.isEmpty()) {
            return count("serverId = ?1 and port = ?2", serverId, port) > 0;
        }
        return count("serverId = ?1 and port = ?2 and id not in ?3", serverId, port, excludedIds) > 0;
    }

    public List<Node> findNodeGroupCandidateNodes(Integer type, Long excludeId) {
        if (excludeId != null) {
            return find("type = ?1 and id != ?2", type, excludeId).list();
        }
        return find("type", type).list();
    }

}
