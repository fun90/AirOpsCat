package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class NodeDeploymentVersionSnapshotDto {
    private Long nodeId;
    private Long serverId;
    private String nodeGroup;
    private Long accessHostId;
    private Integer port;
    private String protocol;
    private String coreType;
    private Integer type;
    private Map<String, Object> inbound;
    private Long outId;
    private Map<String, Object> rule;
    private Integer level;
    private Integer disabled;
    private String name;
    private Integer no;
    private String remark;
    private List<Long> tagIds;
}
