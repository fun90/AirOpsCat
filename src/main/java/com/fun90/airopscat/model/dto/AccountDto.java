package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AccountDto {
    private Long id;
    private Integer level;
    private LocalDateTime fromDate;
    private LocalDateTime toDate;
    private String periodType;
    private String uuid;
    private Integer maxOnlineIps;
    private Integer bandwidth;
    private Integer disabled;
    private Long userId;
    private String userEmail; // 关联用户邮箱，用于展示
}