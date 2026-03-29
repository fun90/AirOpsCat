package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ServerMonitorChartDto {
    private Long serverId;
    private String serverName;
    private String serverIp;
    private String serverHost;
    private Integer hours;
    private List<ServerMonitorPointDto> points = new ArrayList<>();
}
