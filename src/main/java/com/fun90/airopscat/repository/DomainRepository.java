package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Domain;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class DomainRepository implements PanacheRepository<Domain> {

    public Optional<Domain> findByDomain(String domain) {
        return find("domain", domain).firstResultOptional();
    }
    
    public List<Domain> findExpiringDomains(LocalDate date) {
        return find("expireDate <= ?1", date).list();
    }
    
    public List<Domain> findDomainsExpiringBetween(LocalDate startDate, LocalDate endDate) {
        return find("expireDate between ?1 and ?2", startDate, endDate).list();
    }
    
    public List<Domain> searchByKeyword(String keyword, Page page) {
        return find("lower(domain) like lower(?1) or lower(remark) like lower(?1)", 
                   "%" + keyword + "%").page(page).list();
    }
    
    public long countExpiredDomains(LocalDate today) {
        return count("expireDate < ?1", today);
    }
    
    public long countExpiringInOneMonth(LocalDate today, LocalDate inOneMonth) {
        return count("expireDate between ?1 and ?2", today, inOneMonth);
    }
    
    public BigDecimal getTotalDomainCost() {
        return find("select sum(price) from Domain")
                .project(BigDecimal.class)
                .firstResult();
    }
}