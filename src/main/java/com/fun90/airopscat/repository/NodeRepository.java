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
    
    public List<Node> findByType(Integer type) {
        return find("type", type).list();
    }

    public List<Node> findByDeployed(Integer deployed) {
        return find("deployed", deployed).list();
    }

    public List<Node> findByDeployedAndIdIn(Integer deployed, List<Long> nodeIds) {
        return find("deployed = ?1 and id in ?2", deployed, nodeIds).list();
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
    
    public boolean existsByServerIdAndPort(Long serverId, Integer port) {
        return count("serverId = ?1 and port = ?2", serverId, port) > 0;
    }
}