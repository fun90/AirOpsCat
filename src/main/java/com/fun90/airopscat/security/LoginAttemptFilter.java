package com.fun90.airopscat.security;

import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import lombok.extern.slf4j.Slf4j;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录尝试过滤器 - Quarkus版本
 * 实现IP请求频率限制和用户账户锁定检查
 * 这个过滤器在认证之前运行，检查账户锁定状态
 */
@Slf4j
@ApplicationScoped
public class LoginAttemptFilter {

    private static final int MAX_REQUESTS_PER_MINUTE = 20; // 每个 IP 每分钟最大请求数
    private static final long TIME_WINDOW_MS = 60 * 1000; // 1分钟时间窗

    // IP 请求记录 Map：IP -> 时间戳队列
    private final Map<String, Deque<Long>> ipRequestMap = new ConcurrentHashMap<>();

    /**
     * 在应用启动时注册登录尝试过滤器
     */
    void onStart(@Observes StartupEvent event, Router router) {
        log.info("Registering login attempt filter");
        
        // 添加登录尝试过滤器，在认证之前执行
        router.route("/j_security_check")
                .order(1) // 在默认认证处理器之前执行
                .failureHandler(this::handleLoginAttempt);
    }

    /**
     * 处理登录尝试过滤
     */
    private void handleLoginAttempt(RoutingContext context) {
        try {
            String ip = getClientIP(context);
            if (ip == null) {
                context.next();
                return;
            }
            long now = System.currentTimeMillis();

            Deque<Long> timestamps = ipRequestMap.computeIfAbsent(ip, k -> new LinkedList<>());
            synchronized (timestamps) {
                // 移除过期记录
                while (!timestamps.isEmpty() && (now - timestamps.peekFirst()) > TIME_WINDOW_MS) {
                    timestamps.pollFirst();
                }

                if (timestamps.size() >= MAX_REQUESTS_PER_MINUTE) {
                    // 返回429状态码（Too Many Requests）
                    context.response()
                            .setStatusCode(429)
                            .end("您的请求过于频繁，请稍后再试。");
                    return;
                }

                timestamps.addLast(now);
            }
        } catch (Exception e) {
            log.error("Error in login attempt filter", e);
        }
        context.next();
    }


    /**
     * 获取用户名（从表单数据或查询参数）
     */
    private String obtainUsername(RoutingContext context) {
        try {
            return context.request().getFormAttribute("email");
        } catch (Exception e) {
            log.debug("Could not extract email from form attributes");
            return context.request().getParam("email");
        }
    }

    /**
     * 获取客户端真实 IP
     */
    private String getClientIP(RoutingContext context) {
        String xfHeader = context.request().getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty()) {
            String realIp = context.request().getHeader("X-Real-IP");
            if (realIp == null || realIp.isEmpty()) {
                return context.request().remoteAddress().host();
            }
            return realIp;
        }
        return xfHeader.split(",")[0].trim();
    }
}