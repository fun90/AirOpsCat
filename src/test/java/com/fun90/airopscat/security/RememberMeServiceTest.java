package com.fun90.airopscat.security;

import com.fun90.airopscat.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RememberMeServiceTest {

    private RememberMeService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new RememberMeService();
        service.secret = "test-secret";
        service.days = 14;
        user = new User();
        user.setEmail("a@b.com");
        user.setPassword("hash-1");
    }

    @Test
    void validTokenReturnsEmail() {
        assertEquals("a@b.com", service.verify(service.issue(user), e -> user));
    }

    @Test
    void passwordChangeInvalidatesToken() {
        String token = service.issue(user);
        user.setPassword("hash-2");
        assertNull(service.verify(token, e -> user));
    }

    @Test
    void expiredTamperedOrMalformedTokensAreRejected() {
        service.days = -1;
        assertNull(service.verify(service.issue(user), e -> user));

        service.days = 14;
        String[] p = service.issue(user).split("\\.");
        String forgedEmail = Base64.getUrlEncoder().withoutPadding().encodeToString("x@y.com".getBytes());
        assertNull(service.verify(forgedEmail + "." + p[1] + "." + p[2], e -> user));
        assertNull(service.verify("garbage", e -> user));
        assertNull(service.verify("!.x.y", e -> user));
        assertNull(service.verify(service.issue(user), e -> null));
    }
}
