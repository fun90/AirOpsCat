package com.fun90.airopscat.model.dto.maintenance;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ServerMaintenanceStepResultDto {
    private String scriptName;
    private String stepTitle;
    private boolean success;
    private int exitStatus;
    private String stdout;
    private String stderr;
    private String message;
    private long durationMs;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
