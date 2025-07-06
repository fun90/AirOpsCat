package com.fun90.airopscat.model.dto.xray.setting;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

// 出站设置
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class OutboundSetting {
}