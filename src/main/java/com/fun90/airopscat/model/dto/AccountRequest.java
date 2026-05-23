package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AccountRequest {
    private Long id;
    private Long userId;
    private String accountNo;
    private Integer level;
    private Integer nodeMultiple;
    private String nodePrefix;
    private LocalDateTime fromDate;
    private LocalDateTime toDate;
    private String periodType;
    private String uuid;
    private String authCode;
    private Integer maxConnections;
    private Integer bandwidth;
    private Integer downloadMbps;
    private Integer uploadMbps;
    private Integer disabled;
    private String remark; // 账户备注
    private List<Long> tagIds; // 标签ID列表
} 
