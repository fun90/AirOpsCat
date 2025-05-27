package com.fun90.airopscat.model.dto.xray;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fun90.airopscat.model.dto.xray.setting.InboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.StreamSetting;
import lombok.Data;

// 入站配置
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InboundConfig {
    private String tag;
    private String listen;
    private Integer port;
    private String protocol;
    private InboundSetting settings;
    private StreamSetting streamSettings;
}