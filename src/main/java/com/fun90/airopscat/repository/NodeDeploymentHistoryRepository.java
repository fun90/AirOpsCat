package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.NodeDeploymentHistory;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class NodeDeploymentHistoryRepository implements PanacheRepository<NodeDeploymentHistory> {

    public List<NodeDeploymentHistory> findByNodeIdOrderByVersionDesc(Long nodeId) {
        return find("nodeId = ?1 order by version desc", nodeId).list();
    }

    public Optional<NodeDeploymentHistory> findByNodeServerIdAndVersion(Long nodeServerId, Integer version) {
        return find("nodeServerId = ?1 and version = ?2", nodeServerId, version).firstResultOptional();
    }

    public long deleteByServerId(Long serverId) {
        return serverId == null ? 0 : delete("serverId", serverId);
    }

    public long deleteByNodeId(Long nodeId) {
        return nodeId == null ? 0 : delete("nodeId", nodeId);
    }

    public long deleteByNodeServerId(Long nodeServerId) {
        return nodeServerId == null ? 0 : delete("nodeServerId", nodeServerId);
    }

    @Transactional
    public int deleteExpiredHistoryBatch(LocalDateTime cutoffTime, int keepLatestPerNode, int batchSize) {
        return getEntityManager().createNativeQuery("""
                        DELETE FROM node_deployment_history
                        WHERE id IN (
                            SELECT id FROM (
                                SELECT history.id
                                FROM node_deployment_history history
                                LEFT JOIN (
                                    SELECT ranked.id
                                    FROM (
                                        SELECT id,
                                               ROW_NUMBER() OVER (
                                                   PARTITION BY node_id
                                                   ORDER BY version DESC, archived_at DESC, id DESC
                                               ) AS row_num
                                        FROM node_deployment_history
                                    ) ranked
                                    WHERE ranked.row_num <= ?2
                                ) preserved ON preserved.id = history.id
                                WHERE history.archived_at < ?1
                                  AND preserved.id IS NULL
                                ORDER BY history.archived_at ASC, history.id ASC
                                LIMIT ?3
                            ) delete_candidates
                        )
                        """)
                .setParameter(1, cutoffTime)
                .setParameter(2, Math.max(keepLatestPerNode, 0))
                .setParameter(3, Math.max(batchSize, 1))
                .executeUpdate();
    }
}
