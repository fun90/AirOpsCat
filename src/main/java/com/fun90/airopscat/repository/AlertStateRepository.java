package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.AlertState;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class AlertStateRepository implements PanacheRepository<AlertState> {

    public Optional<AlertState> findByIdentity(String alertType, String resourceType, Long resourceId, String fingerprint) {
        return find("alertType = ?1 and resourceType = ?2 and resourceId = ?3 and fingerprint = ?4",
                alertType, resourceType, resourceId, fingerprint).firstResultOptional();
    }

    public List<AlertState> findActiveByAlertType(String alertType) {
        return find("alertType = ?1 and status = ?2", alertType, "ACTIVE").list();
    }

    public long countByStatus(String status) {
        return count("status = ?1", status);
    }

    public PanacheQuery<AlertState> findByFilters(String alertType, String status, String resourceType) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        if (alertType != null && !alertType.isBlank()) {
            conditions.add("alertType = :alertType");
            params.put("alertType", alertType);
        }
        if (status != null && !status.isBlank()) {
            conditions.add("status = :status");
            params.put("status", status);
        }
        if (resourceType != null && !resourceType.isBlank()) {
            conditions.add("resourceType = :resourceType");
            params.put("resourceType", resourceType);
        }

        Sort sort = Sort.by("lastTriggeredTime").descending();
        if (conditions.isEmpty()) {
            return findAll(sort);
        }
        return find(String.join(" and ", conditions), sort, params);
    }
}
