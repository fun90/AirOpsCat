package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.ServerMonitorStats;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@ApplicationScoped
public class ServerMonitorStatsRepository implements PanacheRepository<ServerMonitorStats> {

    public ServerMonitorStats findLatestByServerId(Long serverId) {
        return find("serverId = ?1 order by sampleTime desc", serverId).firstResult();
    }

    public List<ServerMonitorStats> findByServerIdAndSampleTimeBetween(Long serverId, LocalDateTime startTime, LocalDateTime endTime) {
        return find("serverId = ?1 and sampleTime between ?2 and ?3 order by sampleTime asc", serverId, startTime, endTime).list();
    }

    public List<ServerMonitorStats> findLatestListByServerId(Long serverId, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        return find("serverId = ?1 order by sampleTime desc", serverId)
                .page(0, limit)
                .list();
    }

    @Transactional
    public long deleteByServerId(Long serverId) {
        return delete("serverId", serverId);
    }

    @Transactional
    public long deleteBySampleTimeBefore(LocalDateTime cutoffTime) {
        return delete("sampleTime < ?1", cutoffTime);
    }

    @Transactional
    public int deleteBySampleTimeBeforeBatch(LocalDateTime cutoffTime, int batchSize) {
        return getEntityManager().createNativeQuery(
                        "DELETE FROM server_monitor_stats WHERE sample_time < ?1 ORDER BY sample_time LIMIT ?2")
                .setParameter(1, cutoffTime)
                .setParameter(2, Math.max(batchSize, 1))
                .executeUpdate();
    }
}
