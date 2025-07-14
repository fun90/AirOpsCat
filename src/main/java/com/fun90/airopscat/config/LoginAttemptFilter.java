package com.fun90.airopscat.config;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.service.LoginLockService;
import com.fun90.airopscat.service.UserService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录尝试过滤器 - Quarkus版本
 * 实现IP请求频率限制和用户账户锁定检查
 */
@Provider
@PreMatching
@ApplicationScoped
public class LoginAttemptFilter implements ContainerRequestFilter {

    private static final int MAX_REQUESTS_PER_MINUTE = 20; // 每个 IP 每分钟最大请求数
    private static final long TIME_WINDOW_MS = 60 * 1000; // 1分钟时间窗

    @Inject
    UserService userService;
    
    @Inject
    LoginLockService lockService;

    // IP 请求记录 Map：IP -> 时间戳队列
    private final Map<String, Deque<Long>> ipRequestMap = new ConcurrentHashMap<>();

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (isLoginRequest(requestContext)) {
            String ip = getClientIP(requestContext);
            long now = System.currentTimeMillis();

            Deque<Long> timestamps = ipRequestMap.computeIfAbsent(ip, k -> new LinkedList<>());
            synchronized (timestamps) {
                // 移除过期记录
                while (!timestamps.isEmpty() && (now - timestamps.peekFirst()) > TIME_WINDOW_MS) {
                    timestamps.pollFirst();
                }

                if (timestamps.size() >= MAX_REQUESTS_PER_MINUTE) {
                    // 返回429状态码（Too Many Requests）
                    requestContext.abortWith(Response.status(429)
                            .entity("您的请求过于频繁，请稍后再试。")
                            .build());
                    return;
                }

                timestamps.addLast(now);
            }

            // 检查用户账户锁定状态
            String email = obtainUsername(requestContext);
            if (email != null) {
                User user = userService.getByEmail(email);
                if (user != null) {
                    if (!lockService.isAccountNonLocked(user)) {
                        requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                                .entity("您的账户仍处于锁定状态。请稍后再试。")
                                .build());
                        return;
                    }
                }
            }
        }
    }

    /**
     * 判断是否为登录请求
     */
    private boolean isLoginRequest(ContainerRequestContext requestContext) {
        return "POST".equalsIgnoreCase(requestContext.getMethod()) &&
                requestContext.getUriInfo().getPath().equals("/api/login/auth");
    }

    /**
     * 获取用户名（从表单数据或查询参数）
     */
    private String obtainUsername(ContainerRequestContext requestContext) {
        // 由于ContainerRequestFilter无法直接访问表单数据，
        // 这里返回null，在实际的认证处理中会进行用户验证
        return requestContext.getUriInfo().getQueryParameters().getFirst("email");
    }

    /**
     * 获取客户端真实 IP
     */
    private String getClientIP(ContainerRequestContext requestContext) {
        String xfHeader = requestContext.getHeaders().getFirst("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty()) {
            // 从远程地址获取IP（这在Quarkus中可能需要特殊处理）
            return requestContext.getHeaders().getFirst("X-Real-IP");
        }
        return xfHeader.split(",")[0];
    }
}