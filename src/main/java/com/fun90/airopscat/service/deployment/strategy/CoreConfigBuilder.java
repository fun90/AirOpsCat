package com.fun90.airopscat.service.deployment.strategy;

import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.entity.Node;

import java.util.List;

public interface CoreConfigBuilder {

    String build(DeploymentServerContext ctx, List<Node> nodes);

    String getStrategyName();
}
