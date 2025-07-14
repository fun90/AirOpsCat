package com.fun90.airopscat.config;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Quarkus Security配置
 * 使用JPA安全扩展进行用户认证，提供BCrypt密码编码
 */
@ApplicationScoped
public class SecurityConfig {

    private static final Logger LOG = Logger.getLogger(SecurityConfig.class);

    @ConfigProperty(name = "quarkus.security.users.embedded.enabled", defaultValue = "false")
    boolean embeddedUsersEnabled;

    @Inject
    Event<SecurityIdentity> authenticationSuccessEvent;

    @Inject
    Event<AuthenticationRequest> authenticationFailureEvent;

    /**
     * BCrypt密码编码器
     */
    @Named("passwordEncoder")
    @ApplicationScoped
    public BcryptPasswordEncoder passwordEncoder() {
        return new BcryptPasswordEncoder();
    }

    /**
     * BCrypt密码编码器实现
     */
    public static class BcryptPasswordEncoder {
        public String encode(String rawPassword) {
            return BcryptUtil.bcryptHash(rawPassword);
        }

        public boolean matches(String rawPassword, String encodedPassword) {
            return BcryptUtil.matches(rawPassword, encodedPassword);
        }
    }

    /**
     * 自定义认证成功处理
     */
    public Uni<Void> handleAuthenticationSuccess(SecurityIdentity identity, RoutingContext context) {
        return Uni.createFrom().item(() -> {
            if (identity != null && !identity.isAnonymous()) {
                String username = identity.getPrincipal().getName();
                LOG.infof("User %s authenticated successfully", username);
                
                // 触发认证成功事件
                authenticationSuccessEvent.fire(identity);
                
                // 重定向到仪表板
                if (context != null && context.response() != null) {
                    context.response().setStatusCode(302);
                    context.response().putHeader("Location", "/dashboard");
                    context.response().end();
                }
            }
            return null;
        });
    }

    /**
     * 自定义认证失败处理
     */
    public Uni<Void> handleAuthenticationFailure(AuthenticationRequest request, RoutingContext context) {
        return Uni.createFrom().item(() -> {
            LOG.warn("Authentication failed");
            
            // 触发认证失败事件
            authenticationFailureEvent.fire(request);
            
            // 重定向到登录页面
            if (context != null && context.response() != null) {
                context.response().setStatusCode(302);
                context.response().putHeader("Location", "/login?error");
                context.response().end();
            }
            return null;
        });
    }
}

