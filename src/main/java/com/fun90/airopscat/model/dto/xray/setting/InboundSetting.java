package com.fun90.airopscat.model.dto.xray.setting;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
//@JsonDeserialize(using = InboundSettingDeserializer.class)
public abstract class InboundSetting {
    // 可以添加一些公共字段或方法
}