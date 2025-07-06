package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.AccountTrafficStats;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AccountTrafficStatsRepository extends JpaRepository<AccountTrafficStats, Long>, JpaSpecificationExecutor<AccountTrafficStats> {

    List<AccountTrafficStats> findByUserId(Long userId);
    
    List<AccountTrafficStats> findByAccountId(Long accountId);
    
    Page<AccountTrafficStats> findByUserId(Long userId, Pageable pageable);
    
    @Query("SELECT ats FROM AccountTrafficStats ats WHERE ats.userId = :userId AND ats.periodStart >= :startDate AND ats.periodEnd <= :endDate")
    List<AccountTrafficStats> findByUserIdAndPeriod(@Param("userId") Long userId, 
                                                  @Param("startDate") LocalDateTime startDate, 
                                                  @Param("endDate") LocalDateTime endDate);
    
    @Query("SELECT ats FROM AccountTrafficStats ats WHERE ats.accountId = :accountId AND ats.periodStart >= :startDate AND ats.periodEnd <= :endDate")
    List<AccountTrafficStats> findByAccountIdAndPeriod(@Param("accountId") Long accountId, 
                                                     @Param("startDate") LocalDateTime startDate, 
                                                     @Param("endDate") LocalDateTime endDate);
    
    @Query("SELECT SUM(ats.uploadBytes) FROM AccountTrafficStats ats WHERE ats.userId = :userId")
    Long sumUploadBytesByUserId(@Param("userId") Long userId);
    
    @Query("SELECT SUM(ats.downloadBytes) FROM AccountTrafficStats ats WHERE ats.userId = :userId")
    Long sumDownloadBytesByUserId(@Param("userId") Long userId);
    
    @Query("SELECT SUM(ats.uploadBytes) FROM AccountTrafficStats ats WHERE ats.accountId = :accountId")
    Long sumUploadBytesByAccountId(@Param("accountId") Long accountId);
    
    @Query("SELECT SUM(ats.downloadBytes) FROM AccountTrafficStats ats WHERE ats.accountId = :accountId")
    Long sumDownloadBytesByAccountId(@Param("accountId") Long accountId);
    
    /**
     * 查找指定账户在指定时间范围内的流量统计记录（当前时间在时间范围内）
     * @param accountId 账户ID
     * @param currentTime 当前时间
     * @return 匹配的流量统计记录列表
     */
    @Query("SELECT ats FROM AccountTrafficStats ats WHERE ats.accountId = :accountId AND :currentTime BETWEEN ats.periodStart AND ats.periodEnd")
    List<AccountTrafficStats> findByAccountIdAndCurrentTime(@Param("accountId") Long accountId, 
                                                           @Param("currentTime") LocalDateTime currentTime);
}