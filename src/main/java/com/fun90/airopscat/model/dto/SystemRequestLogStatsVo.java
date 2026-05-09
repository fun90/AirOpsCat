package com.fun90.airopscat.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemRequestLogStatsVo {
    private List<SystemRequestLogStatsItemVo> pathStats;
    private List<SystemRequestLogStatsItemVo> trendStats;
    private List<SystemRequestLogStatsItemVo> clientIpStats;
}
