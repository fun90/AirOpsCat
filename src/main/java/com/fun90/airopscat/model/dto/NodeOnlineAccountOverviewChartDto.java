package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class NodeOnlineAccountOverviewChartDto {
    private Integer days;
    private List<NodeOnlineAccountOverviewDailyPointDto> points = new ArrayList<>();
}
