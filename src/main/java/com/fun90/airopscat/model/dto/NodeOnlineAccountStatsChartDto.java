package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class NodeOnlineAccountStatsChartDto {
    private Long nodeId;
    private String nodeName;
    private String nodeDisplayName;
    private String serverIp;
    private String serverHost;
    private Integer days;
    private List<NodeOnlineAccountDailyPointDto> points = new ArrayList<>();
}
