package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.ServerConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServerConfigRepository extends JpaRepository<ServerConfig, Long>, JpaSpecificationExecutor<ServerConfig> {
    Optional<ServerConfig> findByServerIdAndConfigType(Long serverId, String configType);
    
    List<ServerConfig> findByServerId(Long serverId);
    
    List<ServerConfig> findByConfigType(String configType);
    
    @Query("SELECT DISTINCT sc.configType FROM ServerConfig sc")
    List<String> findDistinctConfigTypes();
    
    @Query("SELECT COUNT(sc) FROM ServerConfig sc WHERE sc.configType = :configType")
    long countByConfigType(@Param("configType") String configType);
}