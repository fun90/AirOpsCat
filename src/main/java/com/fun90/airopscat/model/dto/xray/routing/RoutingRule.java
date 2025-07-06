package com.fun90.airopscat.model.dto.xray.routing;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoutingRule {
    private String type;
    private List<String> domain;
    private List<String> ip;
    private List<String> port;
    private List<String> sourcePort;
    private List<String> network;
    private List<String> source;
    private List<String> user;
    private List<String> inboundTag;
    private List<String> protocol;
    private String attrs;
    private String outboundTag;
    private String balancerTag;
    private String ruleTag;
}