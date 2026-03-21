package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class NodeCoreSwitchResponse {
    private String targetCoreType;
    private int requestedCount;
    private int switchedCount;
    private int unchangedCount;
    private int routeRuleUpdatedCount;
    private boolean redeployed;
    private List<Long> switchedNodeIds = new ArrayList<>();
    private List<Long> unchangedNodeIds = new ArrayList<>();
    private List<DeploymentResult> deploymentResults = new ArrayList<>();
}
