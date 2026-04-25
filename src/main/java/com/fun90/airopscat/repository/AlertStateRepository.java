package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.AlertState;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
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
}
