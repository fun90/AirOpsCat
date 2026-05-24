package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VnstatPointDto {
    private LocalDateTime sampledAt;
    private Long rxBytes;
    private Long txBytes;
}
