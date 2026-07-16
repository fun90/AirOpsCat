package com.fun90.airopscat.model.dto;

import lombok.Data;

@Data
public class AccountOnboardingUserRequest {
    private String email;
    private String password;
    private String nickName;
    private String remarkName;
    private String remark;
    private String role;
    private Integer disabled;
}
