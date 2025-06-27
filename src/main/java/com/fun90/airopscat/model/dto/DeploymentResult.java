package com.fun90.airopscat.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeploymentResult {
    private Long nodeId;
    private Long serverId;
    private boolean success;
    private String message;
}