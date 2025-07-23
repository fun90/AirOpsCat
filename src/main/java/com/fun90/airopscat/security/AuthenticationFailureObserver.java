package com.fun90.airopscat.security;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.security.AuthenticationFailedException;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证失败观察者
 * 监听表单认证失败并处理账户锁定逻辑
 */
@Slf4j
@ApplicationScoped
public class AuthenticationFailureObserver {
    private static final int MAX_REQUESTS_PER_MINUTE = 15; // 每个 IP 每分钟最大请求数
    private static final long TIME_WINDOW_MS = 60 * 1000; // 1分钟时间窗

    // IP 请求记录 Map：IP -> 时间戳队列
    private final Map<String, Deque<Long>> ipRequestMap = new ConcurrentHashMap<>();

    @Inject
    AuthenticationHandler authenticationHandler;

    /**
     * 在应用启动时注册失败处理器
     */
    void onStart(@Observes StartupEvent event, Router router) {
        log.info("Registering authentication failure observer");

        // 添加失败处理器，在认证失败后执行
        router.route(HttpMethod.POST, "/j_security_check")
                .order(1)
                .failureHandler(this::handleLoginAttempt)
                .failureHandler(this::handleAuthenticationFailure);
    }

    /**
     * 处理认证失败
     */
    private void handleAuthenticationFailure(RoutingContext context) {
        try {
            Throwable failure = context.failure();
            String email = null;

            // 尝试从请求中获取用户名
            try {
                email = context.request().getFormAttribute("email");
            } catch (Exception e) {
                log.debug("Could not extract email from form attributes");
            }

            // 如果是认证失败异常
            if (failure instanceof AuthenticationFailedException ||
                (failure != null && failure.getMessage() != null &&
                 failure.getMessage().contains("Authentication failed"))) {

                log.debug("Authentication failure detected for user: {}", email);

                final String finalEmail = email;
                final String errorMessage = failure != null ? failure.getMessage() : "认证失败";

                // 在工作线程中处理数据库操作
                context.vertx().executeBlocking(promise -> {
                    try {
                        authenticationHandler.handleAuthenticationFailure(finalEmail, errorMessage, context);
                        promise.complete();
                    } catch (Exception e) {
                        promise.fail(e);
                        log.error("Error in authentication failure handler", e);
                    }
                }, result -> {
                    // 无论成功还是失败，都重定向到登录页面
                    if (!context.response().ended()) {
                        String redirectUrl = "/login?error=true";

                        // 如果context中有编码的错误消息，将其添加到URL中
                        String encodedMessage = context.get("errorMessage");
                        if (encodedMessage != null) {
                            redirectUrl += "&msg=" + encodedMessage;
                        }

                        context.response()
                               .setStatusCode(302)
                               .putHeader("Location", redirectUrl)
                               .end();
                    }
                });
                return;
            }

            // 其他类型的失败，继续默认处理
            context.next();

        } catch (Exception e) {
            log.error("Error in authentication failure handler", e);
            // 发生错误时，重定向到登录页面
            if (!context.response().ended()) {
                context.response()
                       .setStatusCode(302)
                       .putHeader("Location", "/login?error=true")
                       .end();
            }
        }
    }

    /**
     * 处理登录尝试过滤
     */
    private void handleLoginAttempt(RoutingContext context) {
        try {
            String ip = getClientIP(context);
            if (ip == null) {
                log.error("获取IP地址失败");
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
                            .end("请求过于频繁，请稍后再试。");
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