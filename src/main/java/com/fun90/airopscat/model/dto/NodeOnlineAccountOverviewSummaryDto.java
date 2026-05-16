package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class NodeOnlineAccountOverviewSummaryDto {
    private Integer days;
    private Long totalNodeCount;
    private Long nodesWithDataCount;
    private Integer todayLatestOnlineAccountCount;
    private Integer todayPeakOnlineAccountCount;
    private Integer periodPeakOnlineAccountCount;
    private Double periodAverageUniqueOnlineAccountCount;
    private LocalDateTime lastSampleTime;
    private Long monitorIntervalSeconds;
    private boolean dataAvailable;
    private List<NodeOnlineAccountOverviewNodeRankDto> topNodes = new ArrayList<>();
    private List<NodeOnlineAccountOverviewNodeRankDto> lowUsageNodes = new ArrayList<>();
}
