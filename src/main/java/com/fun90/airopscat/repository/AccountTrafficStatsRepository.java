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
}