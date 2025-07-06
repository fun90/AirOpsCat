package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AccountOnlineIpDto {
    private Long id;
    private String accountNo; // 账号编号
    private String clientIp;
    private String nodeIp;
    private LocalDateTime lastOnlineTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    
    // 额外的显示信息
    private String userNickName; // 用户昵称
    private Long accountId; // 账户ID
    private Long userId; // 用户ID
    private String userEmail;
    private String nickName;
} 