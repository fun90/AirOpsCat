package com.fun90.airopscat.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemRequestLogPathStatsVo {
    private String requestPath;
    private List<SystemRequestLogStatsItemVo> dateStats;
}
