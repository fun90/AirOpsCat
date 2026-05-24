package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.ServerVnstatStats;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ServerVnstatStatsRepository implements PanacheRepository<ServerVnstatStats> {

    public Optional<ServerVnstatStats> findLatestByServerIdAndPeriod(Long serverId, int year, int month) {
        return find("serverId = ?1 and periodYear = ?2 and periodMonth = ?3 order by sampledAt desc",
                serverId, (short) year, (byte) month).firstResultOptional();
    }

    public List<ServerVnstatStats> findByServerIdAndSampledAtAfter(Long serverId, LocalDateTime since) {
        return list("serverId = ?1 and sampledAt >= ?2 order by sampledAt asc", serverId, since);
    }

    public long deleteBySampledAtBefore(LocalDateTime cutoff) {
        return delete("sampledAt < ?1", cutoff);
    }
}
