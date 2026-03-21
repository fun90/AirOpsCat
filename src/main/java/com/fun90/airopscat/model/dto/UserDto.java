package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserDto {
    private Long id;
    private String email;
    private String nickName;
    private String remarkName;
    private String remark;
    private String role;
    private Integer disabled;
    private Integer accountCount;
    private int failedAttempts;
    private LocalDateTime lockTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
