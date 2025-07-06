package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Domain;
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
public interface DomainRepository extends JpaRepository<Domain, Long>, JpaSpecificationExecutor<Domain> {

    Optional<Domain> findByDomain(String domain);
    
    @Query("SELECT d FROM Domain d WHERE d.expireDate <= :date")
    List<Domain> findExpiringDomains(@Param("date") LocalDate date);
    
    @Query("SELECT d FROM Domain d WHERE d.expireDate BETWEEN :startDate AND :endDate")
    List<Domain> findDomainsExpiringBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
    
    @Query("SELECT d FROM Domain d WHERE LOWER(d.domain) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(d.remark) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<Domain> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
    
    @Query("SELECT COUNT(d) FROM Domain d WHERE d.expireDate < :today")
    Long countExpiredDomains(@Param("today") LocalDate today);
    
    @Query("SELECT COUNT(d) FROM Domain d WHERE d.expireDate BETWEEN :today AND :inOneMonth")
    Long countExpiringInOneMonth(@Param("today") LocalDate today, @Param("inOneMonth") LocalDate inOneMonth);
    
    @Query("SELECT SUM(d.price) FROM Domain d")
    BigDecimal getTotalDomainCost();
}