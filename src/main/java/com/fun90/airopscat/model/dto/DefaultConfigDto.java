package com.fun90.airopscat.model.dto;

import com.fun90.airopscat.model.dto.xray.InboundConfig;
import lombok.Data;

@Data
public class DefaultConfigDto {
    private String protocol;
    private InboundConfig config;
}
