package com.fun90.airopscat.service.guard;

import com.fun90.airopscat.model.dto.guard.GuardBlockedEntry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountGuardEvaluation {
    private Map<String, GuardBlockedEntry> blockedAccounts;
    private Map<String, AccountGuardStats> statsByAccountNo;
}
