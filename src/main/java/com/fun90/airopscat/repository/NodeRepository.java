package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Node;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NodeRepository extends JpaRepository<Node, Long>, JpaSpecificationExecutor<Node> {

    List<Node> findByServerId(Long serverId);
    
    List<Node> findByType(Integer type);
    
    List<Node> findByDisabled(Integer disabled);

    List<Node> findByDeployed(Integer deployed);

    List<Node> findByLevel(Integer level);
    
    @Query("SELECT n FROM Node n WHERE n.name LIKE %:keyword% OR n.remark LIKE %:keyword%")
    Page<Node> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
    
    @Query("SELECT n FROM Node n WHERE n.serverId = :serverId AND n.disabled = 0")
    List<Node> findActiveNodesByServerId(@Param("serverId") Long serverId);
    
    @Query("SELECT COUNT(n) FROM Node n WHERE n.type = 0")
    Long countProxyNodes();
    
    @Query("SELECT COUNT(n) FROM Node n WHERE n.type = 1")
    Long countLandingNodes();
    
    @Query("SELECT COUNT(n) FROM Node n WHERE n.disabled = 0")
    Long countActiveNodes();
    
    @Query("SELECT COUNT(n) FROM Node n WHERE n.disabled = 1")
    Long countDisabledNodes();
    
    // 检查端口是否已被使用
    boolean existsByServerIdAndPortAndIdNot(Long serverId, Integer port, Long id);
    
    boolean existsByServerIdAndPort(Long serverId, Integer port);
}