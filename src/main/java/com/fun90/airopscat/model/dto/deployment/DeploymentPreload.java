package com.fun90.airopscat.model.dto.deployment;
 
import java.util.List;
import java.util.Map;
 
public record DeploymentPreload(
        List<Long> targetServerIds,
        Map<Long, DeploymentServerContext> serverContexts
) {}
 