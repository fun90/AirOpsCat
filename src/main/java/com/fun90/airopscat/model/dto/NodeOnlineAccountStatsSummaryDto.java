package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NodeOnlineAccountStatsSummaryDto {
    private Long nodeId;
    private String nodeName;
    private String nodeDisplayName;
    private Integer nodeNo;
    private String protocol;
    private String serverIp;
    private String serverHost;
    private Integer days;
    private Integer todayLatestOnlineAccountCount;
    private Integer todayPeakOnlineAccountCount;
    private Integer periodPeakOnlineAccountCount;
    private Double periodAverageUniqueOnlineAccountCount;
    private LocalDateTime lastSampleTime;
    private Long monitorIntervalSeconds;
    private boolean dataAvailable;
}
