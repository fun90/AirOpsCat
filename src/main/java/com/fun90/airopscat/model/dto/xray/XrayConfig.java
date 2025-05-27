package com.fun90.airopscat.model.dto.xray;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class XrayConfig {
    private LogConfig log;
    private Map<String, Object> stats;
    private ApiConfig api;
    private PolicyConfig policy;
    private List<InboundConfig> inbounds;
    private List<OutboundConfig> outbounds;
    private RoutingConfig routing;
}





