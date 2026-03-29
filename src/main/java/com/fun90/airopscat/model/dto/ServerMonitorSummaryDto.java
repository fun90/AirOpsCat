package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ServerMonitorSummaryDto {
    private Long serverId;
    private String serverName;
    private String serverIp;
    private String serverHost;
    private LocalDateTime sampleTime;
    private Double cpuUsage;
    private Integer cpuCores;
    private Double memoryUsage;
    private Long memoryUsedBytes;
    private Long memoryTotalBytes;
    private Long networkRxBytes;
    private Long networkTxBytes;
    private Long networkRxRateBytes;
    private Long networkTxRateBytes;
    private LocalDateTime trafficPeriodStart;
    private LocalDateTime trafficPeriodEnd;
    private Long monitorIntervalSeconds;
    private boolean dataAvailable;
}
