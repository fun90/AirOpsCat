package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.NodeDeploymentHistory;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class NodeDeploymentHistoryRepository implements PanacheRepository<NodeDeploymentHistory> {

    public List<NodeDeploymentHistory> findByNodeIdOrderByVersionDesc(Long nodeId) {
        return find("nodeId = ?1 order by version desc", nodeId).list();
    }

    public Optional<NodeDeploymentHistory> findByNodeIdAndVersion(Long nodeId, Integer version) {
        return find("nodeId = ?1 and version = ?2", nodeId, version).firstResultOptional();
    }
}
