package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Domain;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class DomainRepository implements PanacheRepository<Domain> {

    public List<Domain> findExpiringDomains(LocalDate date) {
        return find("expireDate <= ?1", date).list();
    }

    public List<Domain> findExpiringOnDate(LocalDate date) {
        return find("expireDate = ?1", date).list();
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
