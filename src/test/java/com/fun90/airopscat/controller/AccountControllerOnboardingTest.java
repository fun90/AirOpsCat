package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.AccountOnboardingRequest;
import com.fun90.airopscat.service.AccountOnboardingService;
import com.fun90.airopscat.service.UserEmailAlreadyExistsException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountControllerOnboardingTest {

    @Test
    void shouldMapValidationErrorToBadRequest() {
        assertEquals(400, responseFor(new IllegalArgumentException("参数错误")).getStatus());
    }

    @Test
    void shouldMapDuplicateEmailToConflict() {
        assertEquals(409, responseFor(new UserEmailAlreadyExistsException("邮箱已存在")).getStatus());
    }

    @Test
    void shouldMapMissingResourceToNotFound() {
        assertEquals(404, responseFor(new EntityNotFoundException("用户不存在")).getStatus());
    }

    private Response responseFor(RuntimeException error) {
        AccountController controller = new AccountController();
        controller.accountOnboardingService = new AccountOnboardingService(null, null, null, null) {
            @Override
            public Result onboard(AccountOnboardingRequest request) {
                throw error;
            }
        };
        return controller.onboardAccount(new AccountOnboardingRequest());
    }
}
