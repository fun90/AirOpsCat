package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.ServerNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServerNodeRepository extends JpaRepository<ServerNode, Long> {
    List<ServerNode> findByServerId(Long serverId);
    
    @Query("SELECT sn FROM ServerNode sn WHERE sn.id = :nodeId")
    List<ServerNode> findByNodeId(@Param("nodeId") Long nodeId);
}