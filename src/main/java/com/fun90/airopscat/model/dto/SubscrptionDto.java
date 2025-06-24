package com.fun90.airopscat.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubscrptionDto {
    private String name;

    private String content;

    private String expireDate;

    private Long usedFlow;

    private Long totalFlow;
}