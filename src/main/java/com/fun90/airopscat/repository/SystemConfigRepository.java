package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.SystemConfig;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SystemConfigRepository implements PanacheRepository<SystemConfig> {

    public Optional<SystemConfig> findOptionalByConfigKey(String configKey) {
        return find("configKey", configKey).firstResultOptional();
    }

    public List<SystemConfig> findByGroupKey(String groupKey) {
        return find("groupKey", groupKey).list();
    }
}
