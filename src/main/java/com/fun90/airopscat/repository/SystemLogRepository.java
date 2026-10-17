package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.SystemLog;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SystemLogRepository implements PanacheRepository<SystemLog> {

    public PanacheQuery<SystemLog> findByQuery(String category, String level, String keyword,
                                               LocalDateTime start, LocalDateTime end) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (category != null && !category.isBlank()) {
            conditions.add("category = :category");
            params.put("category", category.trim());
        }
        if (level != null && !level.isBlank()) {
            conditions.add("level = :level");
            params.put("level", level.trim().toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            conditions.add("(lower(message) like :keyword or lower(resourceName) like :keyword)");
            params.put("keyword", "%" + keyword.trim().toLowerCase() + "%");
        }
        if (start != null) {
            conditions.add("createTime >= :start");
            params.put("start", start);
        }
        if (end != null) {
            conditions.add("createTime <= :end");
            params.put("end", end);
        }
        Sort sort = Sort.by("createTime").descending().and("id").descending();
        return conditions.isEmpty() ? findAll(sort) : find(String.join(" and ", conditions), sort, params);
    }

    public long deleteOlderThan(LocalDateTime cutoff) {
        return delete("createTime < ?1", cutoff);
    }
}
