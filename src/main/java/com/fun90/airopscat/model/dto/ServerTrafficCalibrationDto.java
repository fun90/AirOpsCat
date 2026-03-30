package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ServerTrafficCalibrationDto {
    private BigDecimal uploadGb;
    private BigDecimal downloadGb;
    private LocalDateTime periodStartDate;
    private LocalDateTime periodEndDate;
}
