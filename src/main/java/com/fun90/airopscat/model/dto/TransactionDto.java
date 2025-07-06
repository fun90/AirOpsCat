package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TransactionDto {
    private Long id;
    private LocalDateTime transactionDate;
    private BigDecimal amount;
    private Integer type;
    private String typeDescription;
    private String businessTable;
    private Long businessId;
    private String businessName;  // Related business name (e.g., account uuid, domain name, server ip)
    private String description;
    private String paymentMethod;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}