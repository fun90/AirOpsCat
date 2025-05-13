package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long>, JpaSpecificationExecutor<Account> {

    Optional<Account> findByUuid(String uuid);
    
    List<Account> findByDisabled(Integer disabled);
    
    List<Account> findByUserId(Long userId);
    
    List<Account> findByLevel(Integer level);
    
    List<Account> findByPeriodType(String periodType);
    
    @Query("SELECT a FROM Account a WHERE a.userId = :userId AND a.disabled = 0")
    List<Account> findActiveAccountsByUserId(@Param("userId") Long userId);
    
    @Query("SELECT a FROM Account a WHERE a.uuid LIKE %:keyword% OR a.authCode LIKE %:keyword%")
    List<Account> searchByKeyword(@Param("keyword") String keyword);
}