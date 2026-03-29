package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ServerMonitorTrafficCalibrationDto {
    private BigDecimal uploadGb;
    private BigDecimal downloadGb;
}
