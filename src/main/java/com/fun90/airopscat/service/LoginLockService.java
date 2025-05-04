package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class LoginLockService {
    
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final int LOCK_TIME_DURATION = 30; // 单位:分钟
    
    @Autowired
    private UserRepository userRepository;

    @Transactional
    public void increaseFailedAttempts(User user) {
        int newFailedAttempts = user.getFailedAttempts() + 1;
        user.setFailedAttempts(newFailedAttempts);
        userRepository.updateFailedAttempts(newFailedAttempts, user.getEmail());
    }

    @Transactional
    public void resetFailedAttempts(String username) {
        userRepository.updateFailedAttempts(0, username);
    }
    
    public void lock(User user) {
        user.setLockTime(LocalDateTime.now());
        userRepository.save(user);
    }

    public boolean isAccountNonLocked(User user) {
        if (user.getLockTime() == null) {
            return true;
        }
        // 检查锁定时间是否已过期
        return LocalDateTime.now().isAfter(user.getLockTime().plusMinutes(LOCK_TIME_DURATION));
    }
    
    public boolean isAttemptLimitReached(User user) {
        return user.getFailedAttempts() >= MAX_FAILED_ATTEMPTS;
    }

    public int getRemainingAttempts(User user) {
        return MAX_FAILED_ATTEMPTS - user.getFailedAttempts();
    }
}