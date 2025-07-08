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
    private LocalDateTime fromDate;
    private LocalDateTime toDate;
    private String periodType;
    private String uuid;
    private String authCode;
    private Integer maxOnlineIps;
    private Integer speed;
    private Integer bandwidth;
    private Integer disabled;
    private String remark; // 账户备注
    private List<Long> tagIds; // 标签ID列表
} 