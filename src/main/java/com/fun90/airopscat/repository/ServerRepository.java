package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Server;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@ApplicationScoped
public class ServerRepository implements PanacheRepository<Server> {

    public List<Server> findByDisabled(Integer disabled) {
        return find("disabled", disabled).list();
    }

    public List<Server> findByIdIn(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return find("id in ?1", ids).list();
    }

    public List<Long> findIdsByKeyword(String keyword, int limit) {
        String normalizedKeyword = normalizeKeyword(keyword);
        if (normalizedKeyword == null) {
            return List.of();
        }

        String exact = normalizedKeyword;
        String prefix = normalizedKeyword + "%";
        String contains = "%" + normalizedKeyword + "%";
        int safeLimit = Math.max(limit, 1);

        return getEntityManager().createQuery(
                        "select s.id from Server s " +
                                "where s.ip = :exact or s.host = :exact or s.name = :exact or s.supplier = :exact " +
                                "or s.ip like :prefix or s.host like :prefix or s.name like :prefix or s.supplier like :prefix " +
                                "or s.name like :contains or s.supplier like :contains " +
                                "order by s.createTime desc",
                        Long.class)
                .setParameter("exact", exact)
                .setParameter("prefix", prefix)
                .setParameter("contains", contains)
                .setMaxResults(safeLimit)
                .getResultList();
    }

    public List<Server> findExpiringOnDate(LocalDate date) {
        return find("(disabled = 0 or disabled is null) and expireDate = ?1", date).list();
    }

    public List<Server> findMonitorableServers(LocalDate date) {
        return find("(disabled = 0 or disabled is null) " +
                "and (external = 0 or external is null) " +
                "and (expireDate is null or expireDate >= ?1)", date).list();
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

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String normalized = keyword.trim();
        return normalized.isEmpty() ? null : normalized.toLowerCase(Locale.ROOT);
    }
}
