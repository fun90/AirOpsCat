package com.fun90.airopscat.service.guard;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountGuardStats {
    private String accountNo;
    private int totalConnections;
    private int totalIps;
    private int activeNodeCount;
}
