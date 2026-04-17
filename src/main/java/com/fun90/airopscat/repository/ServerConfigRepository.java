package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.ServerConfig;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class ServerConfigRepository implements PanacheRepository<ServerConfig> {
    
    public Optional<ServerConfig> findByServerIdAndConfigType(Long serverId, String configType) {
        return find("serverId = ?1 and configType = ?2", serverId, configType).firstResultOptional();
    }
    
    public List<ServerConfig> findByServerId(Long serverId) {
        return find("serverId", serverId).list();
    }
    
    public long countEnabled() {
        return count("(enabled is null or enabled <> 0)");
    }

    public Set<Long> findEnabledServerIdsByConfigTypes(List<String> configTypes) {
        if (configTypes == null || configTypes.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(getEntityManager().createQuery(
                        "select distinct sc.serverId from ServerConfig sc " +
                                "where sc.serverId is not null and (sc.enabled is null or sc.enabled <> 0) and lower(sc.configType) in ?1",
                        Long.class)
                .setParameter(1, configTypes.stream().map(String::toLowerCase).toList())
                .getResultList());
    }
}
