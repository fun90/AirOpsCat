package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.NodeOnlineAccountDailyStats;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

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

    public List<NodeOnlineAccountDailyStats> findByStatDateBetween(LocalDate startDate, LocalDate endDate) {
        return find("statDate between ?1 and ?2 order by statDate asc, nodeId asc", startDate, endDate).list();
    }
}
