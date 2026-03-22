package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Node;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class NodeRepository implements PanacheRepository<Node> {

    public List<Node> findByServerId(Long serverId) {
        return find("serverId", serverId).list();
    }

    // 一次性查询某个服务器的所有相关节点（作为主服务器或备用服务器）
    public List<Node> findByServerIdOrBackupServerId(Long serverId) {
        return find("serverId = ?1 or backupServerId = ?1", serverId).list();
    }

    public List<Node> findByServerIdsOrBackupServerIds(List<Long> serverIds) {
        return find("serverId in ?1 or backupServerId in ?1", serverIds).list();
    }

    public List<Node> findByIdIn(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return find("id in ?1", ids).list();
    }

    public long countByServerAssociationAndCoreType(Long serverId, String coreType) {
        if (serverId == null || coreType == null || coreType.isBlank()) {
            return 0;
        }
        return count("(serverId = ?1 or backupServerId = ?1) and lower(trim(coreType)) = ?2",
                serverId, coreType.trim().toLowerCase());
    }
    
    public List<Node> findByType(Integer type) {
        return find("type", type).list();
    }

    public List<Node> findByDeployed(Integer deployed) {
        return find("deployed", deployed).list();
    }

    public List<Node> findByDeployedAndIdIn(Integer deployed, List<Long> nodeIds) {
        return find("deployed = ?1 and id in ?2", deployed, nodeIds).list();
    }

    public long fillEmptyCoreType(String coreType) {
        return update("coreType = ?1 where coreType is null or trim(coreType) = ''", coreType);
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
    
    // 检查备用服务器端口是否已被使用
    public boolean existsByBackupServerIdAndPortAndIdNot(Long backupServerId, Integer port, Long id) {
        return count("backupServerId = ?1 and port = ?2 and id != ?3", backupServerId, port, id) > 0;
    }
    
    public boolean existsByBackupServerIdAndPort(Long backupServerId, Integer port) {
        return count("backupServerId = ?1 and port = ?2", backupServerId, port) > 0;
    }
    
    // 检查节点端口是否在主服务器或备用服务器上冲突（一次SQL查询）
    public boolean existsPortConflict(Long serverId, Long backupServerId, Integer port, Long nodeId) {
        if (backupServerId == null) {
            // 只检查主服务器
            if (nodeId == null) {
                return count("serverId = ?1 and port = ?2", serverId, port) > 0;
            } else {
                return count("serverId = ?1 and port = ?2 and id != ?3", serverId, port, nodeId) > 0;
            }
        } else {
            // 检查主服务器和备用服务器的端口冲突
            String query = "(serverId = ?1 and port = ?2) or (backupServerId = ?3 and port = ?4)";
            if (nodeId == null) {
                return count(query, serverId, port, backupServerId, port) > 0;
            } else {
                return count(query + " and id != ?5", serverId, port, backupServerId, port, nodeId) > 0;
            }
        }
    }
}
