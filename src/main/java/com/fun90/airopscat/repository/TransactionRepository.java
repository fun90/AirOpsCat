package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    List<Transaction> findByType(Integer type);
    
    List<Transaction> findByBusinessTableAndBusinessId(String businessTable, Long businessId);
    
    @Query("SELECT t FROM Transaction t WHERE t.transactionDate BETWEEN :startDate AND :endDate")
    List<Transaction> findByDateRange(
            @Param("startDate") LocalDateTime startDate, 
            @Param("endDate") LocalDateTime endDate);
    
    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.type = 0")
    BigDecimal getTotalIncome();
    
    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.type = 1")
    BigDecimal getTotalExpense();
    
    @Query("SELECT SUM(CASE WHEN t.type = 0 THEN t.amount ELSE -t.amount END) FROM Transaction t")
    BigDecimal getNetBalance();
    
    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.type = :type AND t.transactionDate BETWEEN :startDate AND :endDate")
    BigDecimal getSumByTypeAndDateRange(
            @Param("type") Integer type,
            @Param("startDate") LocalDateTime startDate, 
            @Param("endDate") LocalDateTime endDate);
}