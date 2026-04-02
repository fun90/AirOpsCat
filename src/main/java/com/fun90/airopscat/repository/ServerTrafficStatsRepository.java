package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.ServerTrafficStats;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class ServerTrafficStatsRepository implements PanacheRepository<ServerTrafficStats> {

    public List<ServerTrafficStats> findByServerIdAndCurrentTime(Long serverId, LocalDateTime currentTime) {
        return find("serverId = ?1 and periodStart <= ?2 and periodEnd > ?2", serverId, currentTime).list();
    }

    public ServerTrafficStats findByServerIdAndPeriod(Long serverId, LocalDateTime periodStart, LocalDateTime periodEnd) {
        return find("serverId = ?1 and periodStart = ?2 and periodEnd = ?3", serverId, periodStart, periodEnd).firstResult();
    }

    public List<ServerTrafficStats> findByServerIdsAndCurrentTime(List<Long> serverIds, LocalDateTime currentTime) {
        if (serverIds == null || serverIds.isEmpty()) {
            return List.of();
        }
        return find("serverId in ?1 and periodStart <= ?2 and periodEnd > ?2", serverIds, currentTime).list();
    }

    public ServerTrafficStats findLatestByServerId(Long serverId) {
        return find("serverId = ?1 order by periodEnd desc", serverId).firstResult();
    }

    public long deleteByServerId(Long serverId) {
        return delete("serverId", serverId);
    }

    @Transactional
    public int deleteExpiredStatsBatch(LocalDateTime cutoffTime, int batchSize) {
        return getEntityManager().createNativeQuery("""
                        DELETE FROM server_traffic_stats
                        WHERE id IN (
                            SELECT id FROM (
                                SELECT id
                                FROM server_traffic_stats
                                WHERE period_end < ?1
                                ORDER BY period_end ASC, id ASC
                                LIMIT ?2
                            ) delete_candidates
                        )
                        """)
                .setParameter(1, cutoffTime)
                .setParameter(2, Math.max(batchSize, 1))
                .executeUpdate();
    }
}
