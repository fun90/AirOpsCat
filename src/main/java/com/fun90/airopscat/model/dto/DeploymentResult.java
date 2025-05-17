package com.fun90.airopscat.model.dto;

import lombok.Data;

@Data
public class DeploymentResult {
    private Long nodeId;
    private Long serverId;
    private boolean success;
    private String message;
}