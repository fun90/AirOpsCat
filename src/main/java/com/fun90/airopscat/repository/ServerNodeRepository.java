package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.ServerNode;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class ServerNodeRepository implements PanacheRepository<ServerNode> {
    
    public List<ServerNode> findByServerId(Long serverId) {
        return find("serverId", serverId).list();
    }
    
    public List<ServerNode> findByNodeId(Long nodeId) {
        return find("id", nodeId).list();
    }
}