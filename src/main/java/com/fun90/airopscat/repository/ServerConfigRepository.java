package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.ServerConfig;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ServerConfigRepository implements PanacheRepository<ServerConfig> {
    
    public Optional<ServerConfig> findByServerIdAndConfigType(Long serverId, String configType) {
        return find("serverId = ?1 and configType = ?2", serverId, configType).firstResultOptional();
    }
    
    public List<ServerConfig> findByServerId(Long serverId) {
        return find("serverId", serverId).list();
    }
    
    public List<ServerConfig> findByConfigType(String configType) {
        return find("configType", configType).list();
    }
    
    public List<String> findDistinctConfigTypes() {
        return find("select distinct configType from ServerConfig")
                .project(String.class)
                .list();
    }
    
    public long countByConfigType(String configType) {
        return count("configType", configType);
    }

    public long countEnabled() {
        return count("(enabled is null or enabled <> 0)");
    }
}
