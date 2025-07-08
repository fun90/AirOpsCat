package com.fun90.airopscat.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubscrptionDto {
    private String fileName;

    private String content;

    private LocalDateTime expireDate;

    private Long usedFlow;

    private Long totalFlow;
}