package com.fun90.airopscat.security;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 用户状态检查增强器
 * 在认证成功后检查用户是否被禁用并处理登录成功事件
 */
@Slf4j
@ApplicationScoped
public class UserStatusAugmentor implements SecurityIdentityAugmentor {

    @Inject
    UserStatusService userStatusService;

    @Inject
    RememberMeService rememberMeService;

    @Inject
    AuthenticationHandler authenticationHandler;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }

        // 使用context.runBlocking()来处理阻塞的数据库操作
        return context.runBlocking(() -> {
            try {
                // 检查用户状态
                SecurityIdentity checkedIdentity = userStatusService.checkUserStatus(identity);
                
                // 如果用户状态正常，触发登录成功处理
                if (!checkedIdentity.isAnonymous()) {
                    String username = checkedIdentity.getPrincipal().getName();
                    authenticationHandler.handleAuthenticationSuccess(username);
                    log.debug("Authentication success handled for user: {}", username);
                }
                
                return checkedIdentity;
            } catch (Exception e) {
                log.error("Error in user status augmentor for user: {}",  identity.getPrincipal().getName(), e);
                throw e;
            }
        });
    }

    /**
     * 表单登录成功且勾选"记住我"时下发 cookie；在表单认证结束响应之前执行
     */
    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context,
                                         Map<String, Object> attributes) {
        return augment(identity, context).chain(result -> {
            RoutingContext rc = HttpSecurityUtils.getRoutingContextAttribute(attributes);
            if (result.isAnonymous() || rc == null || !rememberMeService.isLoginWithRemember(rc)) {
                return Uni.createFrom().item(result);
            }
            return context.runBlocking(() -> {
                rememberMeService.issueCookie(rc, result.getPrincipal().getName());
                return result;
            });
        });
    }

    @Override
    public int priority() {
        return 10; // 在JPA Security之后执行
    }
}