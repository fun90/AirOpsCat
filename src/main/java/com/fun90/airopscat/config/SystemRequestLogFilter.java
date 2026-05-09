package com.fun90.airopscat.config;

import com.fun90.airopscat.service.SystemRequestLogService;
import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

@Slf4j
@ApplicationScoped
public class SystemRequestLogFilter {

    @Inject
    SystemRequestLogService systemRequestLogService;

    @Inject
    Router router;

    void onStart(@Observes StartupEvent event) {
        router.route()
                .order(-1000)
                .handler(this::handle);
    }

    private void handle(RoutingContext context) {
        String path = context.normalizedPath();
        long startNanos = System.nanoTime();
        LocalDateTime accessTime = LocalDateTime.now();
        String method = context.request().method().name();
        String queryString = context.request().query();
        String clientIp = resolveClientIp(context);
        String userAgent = context.request().getHeader("User-Agent");
        String referer = context.request().getHeader("Referer");

        context.addBodyEndHandler(ignored -> {
            int statusCode = context.response().getStatusCode();
            long durationMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            context.vertx().executeBlocking(() -> {
                try {
                    systemRequestLogService.record(method, path, queryString, clientIp, statusCode,
                            durationMillis, userAgent, referer, accessTime);
                } catch (Exception e) {
                    log.warn("记录系统请求日志失败: {} {}", method, path, e);
                }
                return null;
            }, false);
        });

        context.next();
    }

    private String resolveClientIp(RoutingContext context) {
        String forwardedFor = context.request().getHeader("X-Forwarded-For");
        if (hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = context.request().getHeader("X-Real-IP");
        if (hasText(realIp)) {
            return realIp.trim();
        }
        if (context.request().remoteAddress() != null) {
            return context.request().remoteAddress().host();
        }
        return "unknown";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
