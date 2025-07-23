package com.fun90.airopscat.security;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.service.LoginLockService;
import com.fun90.airopscat.service.UserService;
import com.fun90.airopscat.util.ErrorMessageEncoder;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

/**
 * 登录失败处理器
 * 监听表单认证失败事件并处理账户锁定逻辑
 */
@Slf4j
@ApplicationScoped
public class AuthenticationHandler {

    @Inject
    UserService userService;

    @Inject
    LoginLockService lockService;

    /**
     * 处理认证失败
     * 在表单认证失败时调用
     */
    @Transactional
    public void handleAuthenticationFailure(String username, String errorMessage, RoutingContext context) {
        log.info("Authentication failed for user: {}, reason: {}", username, errorMessage);
        
        if (username != null && !username.trim().isEmpty()) {
            User user = userService.getByEmail(username.trim());
            if (user != null) {
                // 检查账户是否已锁定
                if (lockService.isAccountNonLocked(user)) {
                    // 增加失败次数
                    lockService.increaseFailedAttempts(user);
                    
                    // 重新获取用户以获取最新的失败次数
                    user = userService.getByEmail(username.trim());
                    
                    // 检查是否达到锁定限制
                    if (lockService.isAttemptLimitReached(user)) {
                        lockService.lock(user);
                        log.warn("Account locked for user: {} due to {} failed attempts",
                                username, LoginLockService.MAX_FAILED_ATTEMPTS);
                        
                        // 设置锁定错误消息
                        String lockMessage = String.format(
                            "您的账户因多次登录失败已被锁定。请%d分钟后再试。", 
                            LoginLockService.LOCK_TIME_DURATION);
                        setErrorMessage(context, lockMessage);
                    } else {
                        int remainingAttempts = lockService.getRemainingAttempts(user);
                        log.info("User {} has %d remaining login attempts", username, remainingAttempts);
                        
                        // 设置剩余尝试次数消息
                        String attemptMessage = String.format(
                            "%s 您还有%d次尝试机会。",
                            errorMessage != null ? errorMessage : "登录失败", 
                            remainingAttempts);
                        setErrorMessage(context, attemptMessage);
                    }
                } else {
                    // 账户已锁定
                    long remainingLockTime = lockService.getRemainingLockTime(user);
                    log.warn("Login attempt on locked account: {}, remaining lock time: {} minutes",
                            username, remainingLockTime);
                    
                    String lockMessage = String.format(
                        "您的账户仍处于锁定状态。请%d分钟后再试。", 
                        remainingLockTime);
                    setErrorMessage(context, lockMessage);
                }
            } else {
                // 用户不存在，但不要泄露这个信息
                setErrorMessage(context, "用户名或密码错误");
            }
        }
    }

    /**
     * 处理认证成功
     * 在用户成功登录时调用
     */
    public void handleAuthenticationSuccess(String username) {
        log.info("Authentication successful for user: {}", username);
        
        if (username != null && !username.trim().isEmpty()) {
            // 重置失败尝试次数
            lockService.resetFailedAttempts(username.trim());
            log.debug("Reset failed attempts for user: {}", username);
        }
    }

    /**
     * 设置错误消息到上下文
     */
    private void setErrorMessage(RoutingContext context, String message) {
        String encodedMessage = ErrorMessageEncoder.encode(message);
        context.put("errorMessage", encodedMessage);
    }
}