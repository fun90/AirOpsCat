package com.fun90.airopscat.model.dto.xray;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fun90.airopscat.model.dto.xray.policy.LevelPolicy;
import com.fun90.airopscat.model.dto.xray.policy.SystemPolicy;
import lombok.Data;

import java.util.Map;

// 策略配置
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PolicyConfig {
    private Map<String, LevelPolicy> levels;
    private SystemPolicy system;
}