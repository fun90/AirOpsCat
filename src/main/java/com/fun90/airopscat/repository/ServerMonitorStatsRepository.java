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

    public ServerMonitorStats findPreviousByServerId(Long serverId, LocalDateTime sampleTime) {
        return find("serverId = ?1 and sampleTime < ?2 order by sampleTime desc", serverId, sampleTime).firstResult();
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

    public Long sumRxIncrement(Long serverId, LocalDateTime startTime, LocalDateTime endTimeExclusive) {
        return getEntityManager().createQuery(
                        "select coalesce(sum(s.networkRxIncrementBytes), 0) from ServerMonitorStats s " +
                                "where s.serverId = :serverId and s.sampleTime >= :startTime and s.sampleTime < :endTime",
                        Long.class)
                .setParameter("serverId", serverId)
                .setParameter("startTime", startTime)
                .setParameter("endTime", endTimeExclusive)
                .getSingleResult();
    }

    public Long sumTxIncrement(Long serverId, LocalDateTime startTime, LocalDateTime endTimeExclusive) {
        return getEntityManager().createQuery(
                        "select coalesce(sum(s.networkTxIncrementBytes), 0) from ServerMonitorStats s " +
                                "where s.serverId = :serverId and s.sampleTime >= :startTime and s.sampleTime < :endTime",
                        Long.class)
                .setParameter("serverId", serverId)
                .setParameter("startTime", startTime)
                .setParameter("endTime", endTimeExclusive)
                .getSingleResult();
    }

    @Transactional
    public long deleteByServerId(Long serverId) {
        return delete("serverId", serverId);
    }

    @Transactional
    public long deleteBySampleTimeBefore(LocalDateTime cutoffTime) {
        return delete("sampleTime < ?1", cutoffTime);
    }
}
