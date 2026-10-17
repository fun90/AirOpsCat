package com.fun90.airopscat.security;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.UserRepository;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.function.Function;

/**
 * "记住我"令牌：email.过期时间.HMAC。
 * 签名密钥混入用户当前的密码哈希，修改密码后旧令牌自动失效，且仅凭配置密钥无法伪造。
 */
@ApplicationScoped
public class RememberMeService {

    public static final String COOKIE_NAME = "remember-me";
    private static final String FORM_PARAM = "remember";
    private static final String LOGIN_PATH = "/j_security_check";

    @ConfigProperty(name = "airopscat.crypto.secret-key")
    String secret;

    @ConfigProperty(name = "airopscat.remember-me.days", defaultValue = "14")
    int days;

    @Inject
    UserRepository userRepository;

    /** 表单登录请求且勾选了"记住我" */
    public boolean isLoginWithRemember(RoutingContext rc) {
        return rc.request().method() == HttpMethod.POST
                && rc.normalizedPath().endsWith(LOGIN_PATH)
                && "on".equals(rc.request().getFormAttribute(FORM_PARAM));
    }

    /** 为刚登录成功的用户下发"记住我"cookie */
    @Transactional
    public void issueCookie(RoutingContext rc, String email) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return;
        }
        boolean secure = rc.request().isSSL() || "https".equalsIgnoreCase(rc.request().getHeader("X-Forwarded-Proto"));
        rc.response().addCookie(Cookie.cookie(COOKIE_NAME, issue(user))
                .setPath("/").setHttpOnly(true).setSecure(secure).setSameSite(CookieSameSite.LAX)
                .setMaxAge(days * 86400L));
    }

    /** 校验令牌，通过则返回邮箱，否则返回 null；会查询数据库，需在阻塞线程调用 */
    @Transactional
    public String verify(String token) {
        return verify(token, email -> userRepository.findByEmail(email).orElse(null));
    }

    String verify(String token, Function<String, User> userLoader) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            String email = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            if (Long.parseLong(parts[1]) < Instant.now().getEpochSecond()) {
                return null;
            }
            User user = userLoader.apply(email);
            if (user == null) {
                return null;
            }
            byte[] expected = sign(parts[0] + "." + parts[1], user.getPassword()).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, parts[2].getBytes(StandardCharsets.UTF_8)) ? email : null;
        } catch (IllegalArgumentException e) {
            // Base64 或数字格式错误
            return null;
        }
    }

    String issue(User user) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(user.getEmail().getBytes(StandardCharsets.UTF_8))
                + "." + Instant.now().plus(days, ChronoUnit.DAYS).getEpochSecond();
        return payload + "." + sign(payload, user.getPassword());
    }

    private String sign(String payload, String passwordHash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((secret + "\0" + passwordHash).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
