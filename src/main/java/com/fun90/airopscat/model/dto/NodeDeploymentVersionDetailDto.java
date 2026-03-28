package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NodeDeploymentVersionDetailDto {
    private Integer version;
    private boolean current;
    private LocalDateTime deployedAt;
    private LocalDateTime archivedAt;
    private NodeDeploymentVersionSnapshotDto snapshot;
}
