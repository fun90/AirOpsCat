package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.ServerHost;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Locale;

@ApplicationScoped
public class ServerHostRepository implements PanacheRepository<ServerHost> {

    public List<ServerHost> findByServerId(Long serverId) {
        return find("serverId", Sort.by("sort").ascending().and("id").ascending(), serverId).list();
    }

    public List<ServerHost> findByServerIdIn(List<Long> serverIds) {
        if (serverIds == null || serverIds.isEmpty()) {
            return List.of();
        }
        return find("serverId in ?1", Sort.by("serverId").ascending().and("sort").ascending().and("id").ascending(), serverIds).list();
    }

    public ServerHost findPrimaryByServerId(Long serverId) {
        return find("serverId = ?1 and isPrimary = 1", Sort.by("sort").ascending().and("id").ascending(), serverId).firstResult();
    }

    public List<Long> findServerIdsByKeyword(String keyword, int limit) {
        String normalizedKeyword = normalizeKeyword(keyword);
        if (normalizedKeyword == null) {
            return List.of();
        }

        String exact = normalizedKeyword;
        String prefix = normalizedKeyword + "%";
        String contains = "%" + normalizedKeyword + "%";
        int safeLimit = Math.max(limit, 1);

        return getEntityManager().createQuery(
                        "select distinct sh.serverId from ServerHost sh " +
                                "where sh.host = :exact or sh.host like :prefix or sh.host like :contains " +
                                "order by sh.serverId asc",
                        Long.class)
                .setParameter("exact", exact)
                .setParameter("prefix", prefix)
                .setParameter("contains", contains)
                .setMaxResults(safeLimit)
                .getResultList();
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
                        "select sh.id from ServerHost sh " +
                                "where sh.host = :exact or sh.host like :prefix or sh.host like :contains " +
                                "order by sh.id asc",
                        Long.class)
                .setParameter("exact", exact)
                .setParameter("prefix", prefix)
                .setParameter("contains", contains)
                .setMaxResults(safeLimit)
                .getResultList();
    }

    public long deleteByServerId(Long serverId) {
        return delete("serverId", serverId);
    }

    public long deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return delete("id in ?1", ids);
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String normalized = keyword.trim();
        return normalized.isEmpty() ? null : normalized.toLowerCase(Locale.ROOT);
    }
}
