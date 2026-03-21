package com.fun90.airopscat.model.dto.deployment;

public record RouteRuleSnapshot(
        Long id,
        String name,
        String coreType,
        String ruleType,
        String ruleValue,
        Long outboundNodeId
) {}
