package com.fun90.airopscat.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledTaskDto {
    private String taskKey;
    private String taskName;
    private String description;
    private String groupKey;
    private String groupTitle;
    private String scheduleType;
    private String scheduleValue;
    private boolean paused;
    private boolean scheduled;
    private boolean overdue;
    private LocalDateTime previousFireTime;
    private LocalDateTime nextFireTime;
    private int sortOrder;
}
