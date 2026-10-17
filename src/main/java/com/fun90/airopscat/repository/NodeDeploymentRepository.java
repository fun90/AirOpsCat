package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.NodeDeployment;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class NodeDeploymentRepository implements PanacheRepository<NodeDeployment> {

    public List<NodeDeployment> findByNodeId(Long nodeId) {
        return find("nodeId", nodeId).list();
    }

    public Optional<NodeDeployment> findByNodeServerId(Long nodeServerId) {
        if (nodeServerId == null) {
            return Optional.empty();
        }
        return find("nodeServerId", nodeServerId).firstResultOptional();
    }

    public long deleteByNodeServerId(Long nodeServerId) {
        if (nodeServerId == null) {
            return 0;
        }
        return delete("nodeServerId", nodeServerId);
    }

    public List<NodeDeployment> findByNodeIds(List<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return List.of();
        }
        return find("nodeId in ?1", nodeIds).list();
    }

    public long deleteByNodeId(Long nodeId) {
        if (nodeId == null) {
            return 0;
        }
        return delete("nodeId", nodeId);
    }
}
