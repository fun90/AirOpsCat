package com.fun90.airopscat.model.dto.deployment;

import java.util.List;

public record ServerSnapshot(String transitConfig, List<RouteRuleSnapshot> routeRules) {}
