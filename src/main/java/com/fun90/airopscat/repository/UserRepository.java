package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.User;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@ApplicationScoped
public class UserRepository implements PanacheRepository<User> {

    public Optional<User> findByEmail(String email) {
        return find("email", email).firstResultOptional();
    }

    public List<User> findByRole(String role) {
        return find("role", role).list();
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
                        "select u.id from User u " +
                                "where u.email = :exact or u.nickName = :exact " +
                                "or u.email like :prefix or u.nickName like :prefix " +
                                "or u.nickName like :contains " +
                                "order by u.id desc",
                        Long.class)
                .setParameter("exact", exact)
                .setParameter("prefix", prefix)
                .setParameter("contains", contains)
                .setMaxResults(safeLimit)
                .getResultList();
    }


    @Transactional
    public void updateFailedAttempts(int failedAttempts, String email) {
        update("failedAttempts = ?1 where email = ?2", failedAttempts, email);
    }

    @Transactional
    public void updateLockTime(LocalDateTime lockTime, String email) {
        update("lockTime = ?1 where email = ?2", lockTime, email);
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String normalized = keyword.trim();
        return normalized.isEmpty() ? null : normalized.toLowerCase(Locale.ROOT);
    }
}
