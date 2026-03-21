package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class RouteRuleDto {

    private Long id;
    private String name;
    private String coreType;
    private String ruleType;
    private Object ruleValue;
    private Long outboundNodeId;
    private String outboundNodeName;
    private String outboundServerLabel;
    private Integer enabled;
    private String remark;
    private List<Long> serverIds = new ArrayList<>();
    private List<String> serverNames = new ArrayList<>();
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
