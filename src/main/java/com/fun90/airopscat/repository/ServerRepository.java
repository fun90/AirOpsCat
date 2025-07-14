package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Server;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ServerRepository implements PanacheRepository<Server> {

    public Optional<Server> findByIp(String ip) {
        return find("ip", ip).firstResultOptional();
    }
    
    public List<Server> findByDisabled(Integer disabled) {
        return find("disabled", disabled).list();
    }
    
    public List<Server> findBySupplier(String supplier) {
        return find("supplier", supplier).list();
    }
    
    public List<Server> findExpiringServers(LocalDate date) {
        return find("expireDate is not null and expireDate <= ?1", date).list();
    }

    public Optional<Server> findAvailableServer(Long id, LocalDate now) {
        return find("id = ?1 and disabled = 0 and (expireDate is null or expireDate > ?2)", id, now).firstResultOptional();
    }
    
    public List<Server> findServersExpiringBetween(LocalDate startDate, LocalDate endDate) {
        return find("expireDate is not null and expireDate between ?1 and ?2", startDate, endDate).list();
    }
    
    public List<Server> searchByKeyword(String keyword, Page page) {
        return find("lower(ip) like lower(?1) or lower(host) like lower(?1) or lower(name) like lower(?1) or lower(supplier) like lower(?1)", 
                   "%" + keyword + "%").page(page).list();
    }
    
    public long countActiveServers(LocalDate now) {
        return count("disabled = 0 and (expireDate is null or expireDate > ?1)", now);
    }
    
    public long countExpiredServers(LocalDate now) {
        return count("expireDate is not null and expireDate < ?1", now);
    }
    
    public long countDisabledServers() {
        return count("disabled = 1");
    }
    
    public long countExpiringInOneMonth(LocalDate now, LocalDate inOneMonth) {
        return count("expireDate is not null and expireDate between ?1 and ?2", now, inOneMonth);
    }
    
    public BigDecimal getTotalServerCost() {
        return find("select sum(price) from Server")
                .project(BigDecimal.class)
                .firstResult();
    }
    
    public List<Object[]> countBySupplier() {
        return getEntityManager()
                .createQuery("select s.supplier, count(s) from Server s group by s.supplier", Object[].class)
                .getResultList();
    }
}