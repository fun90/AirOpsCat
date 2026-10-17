package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class NodeRequest {
    private Long id;
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
    private String remark;
    private List<Long> tagIds; // 兼容旧请求：未提供 placements[].tagIds 时应用到所有部署位
    private Long dnsRecordId;
    private List<NodeServerRequest> placements;
} 
