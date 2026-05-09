package com.fun90.airopscat.config;

import com.fun90.airopscat.service.SystemRequestLogService;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

import java.time.LocalDateTime;

@Provider
@Priority(Priorities.USER)
public class SystemRequestLogFilter implements ContainerRequestFilter, ContainerResponseFilter {
    private static final String START_NANOS_PROPERTY = "airopscat.request-log.start-nanos";
    private static final String ACCESS_TIME_PROPERTY = "airopscat.request-log.access-time";

    @Inject
    SystemRequestLogService systemRequestLogService;

    @Context
    RoutingContext routingContext;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        requestContext.setProperty(START_NANOS_PROPERTY, System.nanoTime());
        requestContext.setProperty(ACCESS_TIME_PROPERTY, LocalDateTime.now());
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        try {
            String path = "/" + requestContext.getUriInfo().getPath(false);
            long durationMillis = durationMillis(requestContext.getProperty(START_NANOS_PROPERTY));
            LocalDateTime accessTime = accessTime(requestContext.getProperty(ACCESS_TIME_PROPERTY));
            systemRequestLogService.record(
                    requestContext.getMethod(),
                    path,
                    requestContext.getUriInfo().getRequestUri().getRawQuery(),
                    resolveClientIp(requestContext),
                    responseContext.getStatus(),
                    durationMillis,
                    requestContext.getHeaderString("User-Agent"),
                    requestContext.getHeaderString("Referer"),
                    accessTime
            );
        } catch (Exception ignored) {
            // 日志写入不能影响原请求响应。
        }
    }

    private long durationMillis(Object startNanos) {
        if (startNanos instanceof Long start) {
            return (System.nanoTime() - start) / 1_000_000L;
        }
        return 0L;
    }

    private LocalDateTime accessTime(Object value) {
        return value instanceof LocalDateTime time ? time : LocalDateTime.now();
    }

    private String resolveClientIp(ContainerRequestContext requestContext) {
        String forwardedFor = requestContext.getHeaderString("X-Forwarded-For");
        if (hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = requestContext.getHeaderString("X-Real-IP");
        if (hasText(realIp)) {
            return realIp.trim();
        }
        if (routingContext != null && routingContext.request() != null && routingContext.request().remoteAddress() != null) {
            return routingContext.request().remoteAddress().host();
        }
        return "unknown";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
