package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class NodeRequest {
    private Long id;
    private Long serverId;
    private Integer port;
    private String protocol;
    private Integer type;
    private Map<String, Object> inbound;
    private Long outId;
    private Map<String, Object> rule;
    private Integer level;
    private Integer disabled;
    private String name;
    private String remark;
    private List<Long> tagIds; // 标签ID列表
} 