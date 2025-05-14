package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class DomainDto {
    private Long id;
    private String domain;
    private LocalDate expireDate;
    private BigDecimal price;
    private String remark;
    private Long daysUntilExpiration; // 到期剩余天数
}