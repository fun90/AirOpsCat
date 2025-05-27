package com.fun90.airopscat.model.dto.xray;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fun90.airopscat.model.dto.xray.routing.BalancerConfig;
import com.fun90.airopscat.model.dto.xray.routing.RoutingRule;
import lombok.Data;

import java.util.List;

// 路由配置
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoutingConfig {
    private String domainStrategy;
    private List<RoutingRule> rules;
    private List<BalancerConfig> balancers;
}