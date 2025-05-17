package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.ServerNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServerNodeRepository extends JpaRepository<ServerNode, Long>, JpaSpecificationExecutor<ServerNode> {
    
    List<ServerNode> findByServerId(Long serverId);
    
    List<ServerNode> findByNodeId(Long nodeId);
    
    @Query("SELECT sn FROM ServerNode sn WHERE sn.serverId = :serverId AND sn.port = :port")
    Optional<ServerNode> findByServerIdAndPort(@Param("serverId") Long serverId, @Param("port") Integer port);

}