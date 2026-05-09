package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.dto.SystemRequestLogQuery;
import com.fun90.airopscat.model.dto.SystemRequestLogStatsItemVo;
import com.fun90.airopscat.model.entity.SystemRequestLog;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.TypedQuery;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SystemRequestLogRepository implements PanacheRepository<SystemRequestLog> {

    public PanacheQuery<SystemRequestLog> findByQuery(SystemRequestLogQuery query) {
        QueryParts parts = buildQueryParts(query);
        if (parts.conditions().isEmpty()) {
            return findAll(Sort.by("accessTime").descending());
        }
        return find(String.join(" and ", parts.conditions()), Sort.by("accessTime").descending(), parts.params());
    }

    public List<SystemRequestLogStatsItemVo> statsByPath(SystemRequestLogQuery query, int limit) {
        return aggregate("requestPath", query, limit);
    }

    public List<SystemRequestLogStatsItemVo> statsByClientIp(SystemRequestLogQuery query, int limit) {
        return aggregate("clientIp", query, limit);
    }

    public List<SystemRequestLogStatsItemVo> statsByHour(SystemRequestLogQuery query, int limit) {
        QueryParts parts = buildQueryParts(query);
        String where = parts.conditions().isEmpty() ? "" : " where " + String.join(" and ", parts.conditions());
        String jpql = "select function('date_format', accessTime, '%Y-%m-%d %H:00'), count(id) "
                + "from SystemRequestLog" + where + " group by function('date_format', accessTime, '%Y-%m-%d %H:00') "
                + "order by function('date_format', accessTime, '%Y-%m-%d %H:00') asc";
        TypedQuery<Object[]> typedQuery = getEntityManager().createQuery(jpql, Object[].class);
        setParameters(typedQuery, parts.params());
        return typedQuery.setMaxResults(limit).getResultList()
                .stream()
                .map(row -> new SystemRequestLogStatsItemVo(String.valueOf(row[0]), ((Number) row[1]).longValue()))
                .toList();
    }

    public long deleteExpired(LocalDateTime cutoff) {
        return delete("accessTime < ?1", cutoff);
    }

    private List<SystemRequestLogStatsItemVo> aggregate(String field, SystemRequestLogQuery query, int limit) {
        QueryParts parts = buildQueryParts(query);
        String where = parts.conditions().isEmpty() ? "" : " where " + String.join(" and ", parts.conditions());
        String jpql = "select " + field + ", count(id) from SystemRequestLog" + where
                + " group by " + field + " order by count(id) desc";
        TypedQuery<Object[]> typedQuery = getEntityManager().createQuery(jpql, Object[].class);
        setParameters(typedQuery, parts.params());
        return typedQuery.setMaxResults(limit).getResultList()
                .stream()
                .map(row -> new SystemRequestLogStatsItemVo(String.valueOf(row[0]), ((Number) row[1]).longValue()))
                .toList();
    }

    private QueryParts buildQueryParts(SystemRequestLogQuery query) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (query == null) {
            return new QueryParts(conditions, params);
        }
        if (query.getStartTime() != null) {
            conditions.add("accessTime >= :startTime");
            params.put("startTime", query.getStartTime());
        }
        if (query.getEndTime() != null) {
            conditions.add("accessTime <= :endTime");
            params.put("endTime", query.getEndTime());
        }
        if (hasText(query.getRequestPath())) {
            conditions.add("requestPath like :requestPath escape '\\\\'");
            params.put("requestPath", "%" + escapeLike(query.getRequestPath().trim()) + "%");
        }
        if (hasText(query.getClientIp())) {
            conditions.add("clientIp like :clientIp escape '\\\\'");
            params.put("clientIp", "%" + escapeLike(query.getClientIp().trim()) + "%");
        }
        return new QueryParts(conditions, params);
    }

    private String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private void setParameters(TypedQuery<?> query, Map<String, Object> params) {
        params.forEach(query::setParameter);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record QueryParts(List<String> conditions, Map<String, Object> params) {
    }
}
