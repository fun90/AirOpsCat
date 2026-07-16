package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class AccountOnboardingRequest {
    private Long userId;
    private AccountOnboardingUserRequest newUser;
    private AccountRequest account;
    private BigDecimal amount;
    private String paymentMethod;
}
