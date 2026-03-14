package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ServerTrafficCalibrationDto {
    private BigDecimal uploadGb;
    private BigDecimal downloadGb;
    private LocalDate periodStartDate;
    private LocalDate periodEndDate;
}
