package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class NodeServerRequest {
    private Long serverId;
    private Long accessHostId;
    private Integer exposed;
    private Integer priority;
    private Integer enabled;
    private String name;
    private List<Long> tagIds;
}
