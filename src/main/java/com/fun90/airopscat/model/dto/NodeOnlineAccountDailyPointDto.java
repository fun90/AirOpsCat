package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class NodeOnlineAccountDailyPointDto {
    private LocalDate statDate;
    private Integer latestOnlineAccountCount;
    private Integer peakOnlineAccountCount;
    private Integer uniqueOnlineAccountCount;
    private Integer sampleCount;
    private LocalDateTime lastSampleTime;
}
