package com.fun90.airopscat.config;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.service.LoginLockService;
import com.fun90.airopscat.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class LoginAttemptFilter extends OncePerRequestFilter {

    private static final int MAX_REQUESTS_PER_MINUTE = 20; // 每个 IP 每分钟最大请求数
    private static final long TIME_WINDOW_MS = 60 * 1000; // 1分钟时间窗

    private final UserService userService;
    private final LoginLockService lockService;

    // IP 请求记录 Map：IP -> 时间戳队列
    private final Map<String, Deque<Long>> ipRequestMap = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (isLoginRequest(request)) {
            String ip = getClientIP(request);
            long now = System.currentTimeMillis();

            Deque<Long> timestamps = ipRequestMap.computeIfAbsent(ip, k -> new LinkedList<>());
            synchronized (timestamps) {
                // 移除过期记录
                while (!timestamps.isEmpty() && (now - timestamps.peekFirst()) > TIME_WINDOW_MS) {
                    timestamps.pollFirst();
                }

                if (timestamps.size() >= MAX_REQUESTS_PER_MINUTE) {
                    response.setStatus(429); // 429
                    request.getSession().setAttribute("error", "您的请求过于频繁，请稍后再试。");
                    response.sendRedirect("/login?error");
                    return;
                }

                timestamps.addLast(now);
            }

            String email = obtainUsername(request);
            if (email != null) {
                Optional<User> userOpt = userService.getByEmail(email);
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    if (!lockService.isAccountNonLocked(user)) {
                        request.getSession().setAttribute("error", "您的账户仍处于锁定状态。请稍后再试。");
                        response.sendRedirect("/login?error");
                        return;
                    }
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 判断是否为登录请求
     */
    private boolean isLoginRequest(HttpServletRequest request) {
        return request.getMethod().equalsIgnoreCase("POST") &&
                request.getRequestURI().equals("/api/login/auth");
    }

    /**
     * 获取用户名
     */
    private String obtainUsername(HttpServletRequest request) {
        return request.getParameter("email");
    }

    /**
     * 获取客户端真实 IP
     */
    private String getClientIP(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty()) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0];
    }
}
