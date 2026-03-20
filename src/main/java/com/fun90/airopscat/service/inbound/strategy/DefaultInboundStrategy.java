package com.fun90.airopscat.service.inbound.strategy;

import com.fun90.airopscat.model.dto.DefaultConfigDto;

import java.util.Map;

public interface DefaultInboundStrategy {

    DefaultConfigDto<Map<String, Object>> generateDefaultInbound(String protocol);

    String getStrategyName();
}
