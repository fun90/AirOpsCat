package com.fun90.airopscat.config;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.service.LoginLockService;
import com.fun90.airopscat.service.UserService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.index.qual.NonNegative;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class LoginAttemptFilter extends OncePerRequestFilter {

    private final LoginLockService lockService;

    private final UserService userService;

    private final Cache<String, Integer> attemptsCache = Caffeine.newBuilder()
            .expireAfter(new Expiry<String, Integer>() {
                @Override
                public long expireAfterCreate(String key, Integer value, long currentTime) {
                    return TimeUnit.MINUTES.toNanos(10); // Reset attempts after 10 minutes
                }

                @Override
                public long expireAfterUpdate(String key, Integer value, long currentTime, @NonNegative long currentDuration) {
                    return currentDuration; // Keep the original expiration
                }

                @Override
                public long expireAfterRead(String key, Integer value, long currentTime, @NonNegative long currentDuration) {
                    return currentDuration; // Keep the original expiration
                }
            }).build();

    // Cache for blocked IPs
    private final int MAX_DURATION_MINUTES = 15;
    private final Cache<String, Boolean> blockedIpsCache = Caffeine.newBuilder()
            .expireAfterWrite(MAX_DURATION_MINUTES, TimeUnit.MINUTES) // Block duration of 15 minutes
            .build();

    private final int MAX_ATTEMPTS = 100; // Maximum attempts allowed
    
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        
        // 只检查登录请求
        if (request.getServletPath().equals("/api/login/auth") && request.getMethod().equals("POST")) {
            String email = request.getParameter("email");
            Optional<User> userOptional = userService.getByEmail(email);
            if (userOptional.isPresent()) {
                if (!lockService.isAccountNonLocked(userOptional.get())) {
                    request.getSession().setAttribute("error", "您的账户仍处于锁定状态。请稍后再试。");
                    response.sendRedirect("/login?error");
                    return;
                }
            }

            // Get client IP
            String clientIp = request.getRemoteAddr();

            // Check if the IP is blocked
            Boolean isBlocked = blockedIpsCache.getIfPresent(clientIp);
            if (isBlocked != null && isBlocked) {
                response.setStatus(429);
                response.getWriter().write("Too many login attempts. Please try again later.");
                return;
            }

            // Get current attempts count or initialize with 0
            Integer attempts = attemptsCache.getIfPresent(clientIp);
            int currentAttempts = attempts != null ? attempts : 0;

            // Increment the attempts count
            currentAttempts++;
            attemptsCache.put(clientIp, currentAttempts);

            // Check if max attempts reached
            if (currentAttempts > MAX_ATTEMPTS) {
                // Block the IP
                blockedIpsCache.put(clientIp, true);
                // Reset the attempts count
                attemptsCache.invalidate(clientIp);

                request.getSession().setAttribute("error", "请求过于频繁，请" + MAX_DURATION_MINUTES + "分钟后。");
                response.sendRedirect("/login?error");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}