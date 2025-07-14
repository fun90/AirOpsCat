package com.fun90.airopscat.security;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 用户状态检查增强器
 * 在认证成功后检查用户是否被禁用
 */
@ApplicationScoped
public class UserStatusAugmentor implements SecurityIdentityAugmentor {

    @Inject
    UserStatusService userStatusService;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }

        // 使用context.runBlocking()来处理阻塞的数据库操作
        return context.runBlocking(() -> userStatusService.checkUserStatus(identity));
    }

    @Override
    public int priority() {
        return 10; // 在JPA Security之后执行
    }
}