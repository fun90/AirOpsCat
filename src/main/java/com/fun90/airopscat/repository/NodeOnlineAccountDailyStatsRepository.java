package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.NodeOnlineAccountDailyStats;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class NodeOnlineAccountDailyStatsRepository implements PanacheRepository<NodeOnlineAccountDailyStats> {

    public NodeOnlineAccountDailyStats findByNodeIdAndStatDate(Long nodeId, LocalDate statDate) {
        return find("nodeId = ?1 and statDate = ?2", nodeId, statDate).firstResult();
    }

    public List<NodeOnlineAccountDailyStats> findByNodeIdAndStatDateBetween(Long nodeId, LocalDate startDate, LocalDate endDate) {
        return find("nodeId = ?1 and statDate between ?2 and ?3 order by statDate asc", nodeId, startDate, endDate).list();
    }

    @Transactional
    public int deleteByStatDateBeforeBatch(LocalDate cutoffDate, int batchSize) {
        return getEntityManager().createNativeQuery(
                        "DELETE FROM node_online_account_daily_stats WHERE stat_date < ?1 ORDER BY stat_date, id LIMIT ?2")
                .setParameter(1, cutoffDate)
                .setParameter(2, Math.max(batchSize, 1))
                .executeUpdate();
    }

    public List<NodeOnlineAccountDailyStats> findByStatDateBetween(LocalDate startDate, LocalDate endDate) {
        return find("statDate between ?1 and ?2 order by statDate asc, nodeId asc", startDate, endDate).list();
    }
}
