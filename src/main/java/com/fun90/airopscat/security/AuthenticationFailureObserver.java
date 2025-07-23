package com.fun90.airopscat.security;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.security.AuthenticationFailedException;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * 认证失败观察者
 * 监听表单认证失败并处理账户锁定逻辑
 */
@Slf4j
@ApplicationScoped
public class AuthenticationFailureObserver {

    @Inject
    LoginFailureHandler loginFailureHandler;

    /**
     * 在应用启动时注册失败处理器
     */
    void onStart(@Observes StartupEvent event, Router router, Vertx vertx) {
        log.info("Registering authentication failure observer");

        // 添加失败处理器，在认证失败后执行
        router.route(HttpMethod.POST, "/j_security_check")
                .order(10000) // 在默认认证处理器之后执行
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
                        loginFailureHandler.handleAuthenticationFailure(finalEmail, errorMessage, context);
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
}