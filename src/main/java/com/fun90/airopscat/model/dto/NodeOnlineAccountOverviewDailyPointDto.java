package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class NodeOnlineAccountOverviewDailyPointDto {
    private LocalDate statDate;
    private Integer totalLatestOnlineAccountCount;
    private Integer totalPeakOnlineAccountCount;
    private Integer totalUniqueOnlineAccountCount;
    private Integer activeNodeCount;
}
