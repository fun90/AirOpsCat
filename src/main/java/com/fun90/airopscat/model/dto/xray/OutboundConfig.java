package com.fun90.airopscat.model.dto.xray;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fun90.airopscat.model.dto.xray.setting.OutboundSettings;
import com.fun90.airopscat.model.dto.xray.setting.StreamSetting;
import lombok.Data;

// 出站配置
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OutboundConfig {
    private String tag;
    private String protocol;
    private OutboundSettings settings;
    private StreamSetting streamSettings;
}