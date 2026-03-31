package com.fun90.airopscat.model.dto;

import com.fun90.airopscat.model.enums.DnsProviderCheckStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DnsProviderTestResponse {
    private boolean success;
    private DnsProviderCheckStatus status;
    private String message;
    private LocalDateTime checkTime;
}
