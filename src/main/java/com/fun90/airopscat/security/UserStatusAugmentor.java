package com.fun90.airopscat.security;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * 用户状态检查增强器
 * 在认证成功后检查用户是否被禁用并处理登录成功事件
 */
@ApplicationScoped
public class UserStatusAugmentor implements SecurityIdentityAugmentor {

    private static final Logger log = Logger.getLogger(UserStatusAugmentor.class);

    @Inject
    UserStatusService userStatusService;

    @Inject
    LoginFailureHandler loginFailureHandler;

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
                    loginFailureHandler.handleAuthenticationSuccess(username);
                    log.debugf("Authentication success handled for user: %s", username);
                }
                
                return checkedIdentity;
            } catch (Exception e) {
                log.errorf(e, "Error in user status augmentor for user: %s", 
                          identity.getPrincipal().getName());
                throw e;
            }
        });
    }

    @Override
    public int priority() {
        return 10; // 在JPA Security之后执行
    }
}