package com.fun90.airopscat.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemRequestLogQuery {
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String requestPath;
    private String clientIp;
}
