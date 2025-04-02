package com.fun90.airopscat.model.dto;

import lombok.Data;

@Data
public class UserDto {
    private Long id;
    private String email;
    private String nickName;
    private String role;
}