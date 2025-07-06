package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.AccountOnlineIp;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 在线IP记录仓库
 */
@Repository
public interface AccountOnlineIpRepository extends JpaRepository<AccountOnlineIp, Long>, JpaSpecificationExecutor<AccountOnlineIp> {

    /**
     * 根据accountNo、clientIp和nodeIp查找在线记录
     */
    Optional<AccountOnlineIp> findByAccountNoAndClientIpAndNodeIp(String accountNo, String clientIp, String nodeIp);
    
    /**
     * 根据accountNo查找在线记录
     */
    List<AccountOnlineIp> findByAccountNo(String accountNo);
    
    /**
     * 根据accountNo查找在指定时间之后的在线记录
     */
    List<AccountOnlineIp> findByAccountNoAndLastOnlineTimeAfter(String accountNo, LocalDateTime afterTime);
    
    /**
     * 查找在指定时间之后的所有在线记录
     */
    List<AccountOnlineIp> findByLastOnlineTimeAfter(LocalDateTime afterTime);
    
    /**
     * 根据nodeIp查找在线记录
     */
    List<AccountOnlineIp> findByNodeIp(String nodeIp);
    
    /**
     * 删除指定accountNo的所有在线记录
     */
    void deleteByAccountNo(String accountNo);
    
    /**
     * 删除过期的在线记录
     */
    @Modifying
    @Query("DELETE FROM AccountOnlineIp a WHERE a.lastOnlineTime < :expireTime")
    void deleteExpiredRecords(@Param("expireTime") LocalDateTime expireTime);
} 