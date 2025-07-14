package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.User;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class UserRepository implements PanacheRepository<User> {

    public Optional<User> findByEmail(String email) {
        return find("email", email).firstResultOptional();
    }

    public List<User> findByDisabled(Short disabled) {
        return find("disabled", disabled).list();
    }

    public List<User> findByRole(String role) {
        return find("role", role).list();
    }

    public List<User> findByReferrer(Integer referrer) {
        return find("referrer", referrer).list();
    }

    public List<User> searchByKeyword(String keyword) {
        return find("nickName like ?1 or email like ?1 or role = ?2", 
                   "%" + keyword + "%", keyword).list();
    }

    @Transactional
    public void updateFailedAttempts(int failedAttempts, String email) {
        update("failedAttempts = ?1 where email = ?2", failedAttempts, email);
    }

    public List<User> findActiveUsers() {
        return find("disabled = 0 or disabled is null").list();
    }

    public long countActiveUsers() {
        return count("disabled = 0 or disabled is null");
    }

    public List<User> findUsersByRoleAndStatus(String role, boolean active) {
        if (active) {
            return find("role = ?1 and (disabled = 0 or disabled is null)", role).list();
        } else {
            return find("role = ?1 and disabled = 1", role).list();
        }
    }
}