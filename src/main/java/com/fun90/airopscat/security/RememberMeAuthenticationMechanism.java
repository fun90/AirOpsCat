package com.fun90.airopscat.security;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * 表单 session cookie 失效后，用"记住我"cookie 恢复登录。
 * 优先级低于表单认证，仅在其未能认证时生效；挑战（跳转登录页）仍由表单认证负责。
 */
@ApplicationScoped
public class RememberMeAuthenticationMechanism implements HttpAuthenticationMechanism {

    @Inject
    RememberMeService rememberMe;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext rc, IdentityProviderManager identityProviderManager) {
        Cookie cookie = rc.request().getCookie(RememberMeService.COOKIE_NAME);
        // 登录 POST 已由表单认证处理并可能已结束响应
        if (cookie == null || rc.response().ended() || rc.normalizedPath().endsWith("/j_security_check")) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(() -> rememberMe.verify(cookie.getValue()))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .chain(email -> email == null
                        ? Uni.createFrom().<SecurityIdentity>nullItem()
                        : identityProviderManager.authenticate(HttpSecurityUtils.setRoutingContextAttribute(
                                new TrustedAuthenticationRequest(email), rc)))
                // 账户被锁定/禁用等情况按未认证处理，交给表单认证跳转登录页
                .onFailure().recoverWithNull();
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext rc) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(TrustedAuthenticationRequest.class);
    }

    @Override
    public int getPriority() {
        return 500;
    }
}
