package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RouteRuleRequest {

    private String name;
    private String coreType;
    private String ruleType;
    private Object ruleValue;
    private Long outboundNodeId;
    private Integer enabled;
    private String remark;
    private List<Long> serverIds = new ArrayList<>();
}
