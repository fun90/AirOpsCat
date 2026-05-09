package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.dto.SystemRequestLogQuery;
import com.fun90.airopscat.model.dto.SystemRequestLogPathStatsVo;
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
    private static final String LIKE_ESCAPE_CLAUSE = " escape '!'";

    public PanacheQuery<SystemRequestLog> findByQuery(SystemRequestLogQuery query) {
        QueryParts parts = buildQueryParts(query);
        if (parts.conditions().isEmpty()) {
            return findAll(Sort.by("accessTime").descending());
        }
        return find(String.join(" and ", parts.conditions()), Sort.by("accessTime").descending(), parts.params());
    }

    public List<SystemRequestLogPathStatsVo> statsByPathAndDate(SystemRequestLogQuery query, int pathLimit) {
        List<String> paths = aggregate("requestPath", query, pathLimit)
                .stream()
                .map(SystemRequestLogStatsItemVo::getLabel)
                .toList();
        if (paths.isEmpty()) {
            return List.of();
        }

        QueryParts parts = buildQueryParts(query);
        List<String> conditions = new ArrayList<>(parts.conditions());
        Map<String, Object> params = new HashMap<>(parts.params());
        conditions.add("requestPath in :requestPaths");
        params.put("requestPaths", paths);

        String where = " where " + String.join(" and ", conditions);
        String jpql = "select requestPath, function('date_format', accessTime, '%Y-%m-%d'), count(id) "
                + "from SystemRequestLog" + where
                + " group by requestPath, function('date_format', accessTime, '%Y-%m-%d') "
                + "order by function('date_format', accessTime, '%Y-%m-%d') asc";
        TypedQuery<Object[]> typedQuery = getEntityManager().createQuery(jpql, Object[].class);
        setParameters(typedQuery, params);

        Map<String, List<SystemRequestLogStatsItemVo>> grouped = new HashMap<>();
        typedQuery.getResultList().forEach(row -> grouped
                .computeIfAbsent(String.valueOf(row[0]), key -> new ArrayList<>())
                .add(new SystemRequestLogStatsItemVo(String.valueOf(row[1]), ((Number) row[2]).longValue())));

        return paths.stream()
                .map(path -> new SystemRequestLogPathStatsVo(path, grouped.getOrDefault(path, List.of())))
                .toList();
    }

    public List<SystemRequestLogStatsItemVo> statsByClientIp(SystemRequestLogQuery query, int limit) {
        return aggregate("clientIp", query, limit);
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
            conditions.add("requestPath like :requestPath" + LIKE_ESCAPE_CLAUSE);
            params.put("requestPath", "%" + escapeLike(query.getRequestPath().trim()) + "%");
        }
        if (hasText(query.getClientIp())) {
            conditions.add("clientIp like :clientIp" + LIKE_ESCAPE_CLAUSE);
            params.put("clientIp", "%" + escapeLike(query.getClientIp().trim()) + "%");
        }
        return new QueryParts(conditions, params);
    }

    private String escapeLike(String value) {
        return value
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
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
