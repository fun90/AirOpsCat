package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;

@ApplicationScoped
public class LoginLockService {
    
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final int LOCK_TIME_DURATION = 30; // 单位:分钟
    
    @Inject
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
    
    @Transactional
    public void lock(User user) {
        user.setLockTime(LocalDateTime.now());
        userRepository.updateLockTime(user.getLockTime(), user.getEmail());
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

    /**
     * 获取剩余锁定时间（分钟）
     */
    public long getRemainingLockTime(User user) {
        if (user.getLockTime() == null) {
            return 0;
        }
        LocalDateTime unlockTime = user.getLockTime().plusMinutes(LOCK_TIME_DURATION);
        LocalDateTime now = LocalDateTime.now();
        
        if (now.isAfter(unlockTime)) {
            return 0;
        }
        
        // 计算剩余分钟数
        return java.time.Duration.between(now, unlockTime).toMinutes() + 1;
    }
}