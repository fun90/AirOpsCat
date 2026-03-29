package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.ServerMonitorStats;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class ServerMonitorStatsRepository implements PanacheRepository<ServerMonitorStats> {

    public ServerMonitorStats findLatestByServerId(Long serverId) {
        return find("serverId = ?1 order by sampleTime desc", serverId).firstResult();
    }

    public List<ServerMonitorStats> findByServerIdAndSampleTimeAfter(Long serverId, LocalDateTime startTime) {
        return find("serverId = ?1 and sampleTime >= ?2 order by sampleTime asc", serverId, startTime).list();
    }

    @Transactional
    public long deleteByServerId(Long serverId) {
        return delete("serverId", serverId);
    }
}
