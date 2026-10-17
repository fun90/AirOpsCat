package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class NodeServerDto {
    private Long id;
    private Long nodeId;
    private Long serverId;
    private String serverName;
    private String serverIp;
    private String serverHost;
    private Long accessHostId;
    private String accessHost;
    private Integer exposed;
    private Integer priority;
    private Integer enabled;
    private List<Long> tagIds;
    private Integer deployed;
}
