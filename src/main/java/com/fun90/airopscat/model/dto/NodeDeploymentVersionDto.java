package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NodeDeploymentVersionDto {
    private Integer version;
    private boolean current;
    private Long serverId;
    private String nodeGroup;
    private String coreType;
    private String protocol;
    private Integer type;
    private Integer port;
    private Integer disabled;
    private LocalDateTime deployedAt;
    private LocalDateTime archivedAt;
}
