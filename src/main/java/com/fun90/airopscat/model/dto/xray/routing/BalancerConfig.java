package com.fun90.airopscat.model.dto.xray.routing;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BalancerConfig {
    private String tag;
    private List<String> selector;
    private String strategy;
}