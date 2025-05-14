package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Server;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ServerRepository extends JpaRepository<Server, Long>, JpaSpecificationExecutor<Server> {

    Optional<Server> findByIp(String ip);
    
    List<Server> findByDisabled(Integer disabled);
    
    List<Server> findBySupplier(String supplier);
    
    @Query("SELECT s FROM Server s WHERE s.expireDate IS NOT NULL AND s.expireDate <= :date")
    List<Server> findExpiringServers(@Param("date") LocalDate date);
    
    @Query("SELECT s FROM Server s WHERE s.expireDate IS NOT NULL AND s.expireDate BETWEEN :startDate AND :endDate")
    List<Server> findServersExpiringBetween(
            @Param("startDate") LocalDate startDate, 
            @Param("endDate") LocalDate endDate);
    
    @Query("SELECT s FROM Server s WHERE LOWER(s.ip) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(s.host) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(s.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(s.supplier) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<Server> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
    
    @Query("SELECT COUNT(s) FROM Server s WHERE s.disabled = 0 AND (s.expireDate IS NULL OR s.expireDate > :now)")
    Long countActiveServers(@Param("now") LocalDate now);
    
    @Query("SELECT COUNT(s) FROM Server s WHERE s.expireDate IS NOT NULL AND s.expireDate < :now")
    Long countExpiredServers(@Param("now") LocalDate now);
    
    @Query("SELECT COUNT(s) FROM Server s WHERE s.disabled = 1")
    Long countDisabledServers();
    
    @Query("SELECT COUNT(s) FROM Server s WHERE s.expireDate IS NOT NULL AND s.expireDate BETWEEN :now AND :inOneMonth")
    Long countExpiringInOneMonth(
            @Param("now") LocalDate now, 
            @Param("inOneMonth") LocalDate inOneMonth);
    
    @Query("SELECT SUM(s.price) FROM Server s")
    BigDecimal getTotalServerCost();
    
    @Query("SELECT s.supplier, COUNT(s) FROM Server s GROUP BY s.supplier")
    List<Object[]> countBySupplier();
}