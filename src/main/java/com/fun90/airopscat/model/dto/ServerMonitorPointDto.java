package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ServerMonitorPointDto {
    private LocalDateTime sampleTime;
    private Double cpuUsage;
    private Double memoryUsage;
    private Long memoryUsedBytes;
    private Long memoryTotalBytes;
    private Long networkRxRateBytes;
    private Long networkTxRateBytes;
}
