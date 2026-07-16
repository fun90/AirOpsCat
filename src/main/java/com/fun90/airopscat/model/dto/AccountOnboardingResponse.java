package com.fun90.airopscat.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountOnboardingResponse {
    private UserDto user;
    private AccountDto account;
    private TransactionDto transaction;
}
