package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long>, JpaSpecificationExecutor<Account> {

    Optional<Account> findByUuid(String uuid);
    
    List<Account> findByUserId(Long userId);
    
    List<Account> findByDisabled(Integer disabled);
    
    @Query("SELECT a FROM Account a WHERE a.toDate IS NOT NULL AND a.toDate <= :date")
    List<Account> findExpiringAccounts(@Param("date") LocalDateTime date);
    
    @Query("SELECT a FROM Account a WHERE a.toDate IS NOT NULL AND a.toDate BETWEEN :startDate AND :endDate")
    List<Account> findAccountsExpiringBetween(
            @Param("startDate") LocalDateTime startDate, 
            @Param("endDate") LocalDateTime endDate);
    
//    @Query("SELECT a FROM Account a WHERE a.userId = :userId AND (LOWER(a.uuid) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(a.authCode) LIKE LOWER(CONCAT('%', :keyword, '%')))")
//    Page<Account> searchByUserIdAndKeyword(
//            @Param("userId") Long userId,
//            @Param("keyword") String keyword,
//            Pageable pageable);
    
    @Query("SELECT COUNT(a) FROM Account a WHERE a.disabled = 0 AND (a.toDate IS NULL OR a.toDate > :now)")
    Long countActiveAccounts(@Param("now") LocalDateTime now);
    
    @Query("SELECT COUNT(a) FROM Account a WHERE a.toDate IS NOT NULL AND a.toDate < :now")
    Long countExpiredAccounts(@Param("now") LocalDateTime now);
    
    @Query("SELECT COUNT(a) FROM Account a WHERE a.disabled = 1")
    Long countDisabledAccounts();
    
    @Query("SELECT COUNT(a) FROM Account a WHERE a.toDate IS NOT NULL AND a.toDate BETWEEN :now AND :inOneWeek")
    Long countExpiringInOneWeek(
            @Param("now") LocalDateTime now, 
            @Param("inOneWeek") LocalDateTime inOneWeek);
    
    @Query("SELECT COUNT(a) FROM Account a WHERE a.userId = :userId")
    Long countByUserId(@Param("userId") Long userId);
}