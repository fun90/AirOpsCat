package com.fun90.airopscat.model.dto;

import com.fun90.airopscat.model.enums.DnsSyncStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DomainDnsPushResponse {
    private Long domainId;
    private int pushedRecordCount;
    private int deletedCount;
    private int updatedCount;
    private int createdCount;
    private DnsSyncStatus syncStatus;
    private LocalDateTime lastSyncTime;
    private String message;
}
