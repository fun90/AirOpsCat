package com.fun90.airopscat.model.dto;

import com.fun90.airopscat.model.enums.DnsSyncStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DomainDnsPullResponse {
    private Long domainId;
    private int pulledRecordCount;
    private DnsSyncStatus syncStatus;
    private LocalDateTime lastSyncTime;
    private String message;
}
