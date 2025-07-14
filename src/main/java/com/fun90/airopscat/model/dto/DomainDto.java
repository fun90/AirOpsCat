package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class DomainDto {
    private Long id;
    private String domain;
    private LocalDate expireDate;
    private BigDecimal price;
    private String supplier;
    private String remark;
    private Long daysUntilExpiration; // 到期剩余天数
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}