package com.fun90.airopscat.model.dto.xray;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fun90.airopscat.config.jackson.OutboundConfigDeserializer;
import com.fun90.airopscat.model.dto.xray.setting.OutboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.StreamSetting;
import lombok.Data;

// 出站配置
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(using = OutboundConfigDeserializer.class)
public class OutboundConfig {
    private String tag;
    private String protocol;
    private OutboundSetting settings;
    private StreamSetting streamSettings;
}