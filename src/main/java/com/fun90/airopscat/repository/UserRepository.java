package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.User;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class UserRepository implements PanacheRepository<User> {

    public Optional<User> findByEmail(String email) {
        return find("email", email).firstResultOptional();
    }

    public List<User> findByRole(String role) {
        return find("role", role).list();
    }


    @Transactional
    public void updateFailedAttempts(int failedAttempts, String email) {
        update("failedAttempts = ?1 where email = ?2", failedAttempts, email);
    }

    @Transactional
    public void updateLockTime(LocalDateTime lockTime, String email) {
        update("lockTime = ?1 where email = ?2", lockTime, email);
    }

}