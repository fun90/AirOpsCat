package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AccountTrafficStatsDto {
    private Long id;
    private Long userId;
    private String userEmail; // 关联用户的邮箱
    private Long accountId;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private Long uploadBytes;
    private Long downloadBytes;
    private Long totalBytes; // 上传和下载流量的总和
}