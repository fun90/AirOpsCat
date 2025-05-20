package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.ServerConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ServerConfigRepository extends JpaRepository<ServerConfig, Long>, JpaSpecificationExecutor<ServerConfig> {
    Optional<ServerConfig> findByServerIdAndConfigType(Long serverId, String configType);
}