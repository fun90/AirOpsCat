package com.fun90.airopscat.model.dto.xray.setting.inbound;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fun90.airopscat.model.dto.xray.setting.InboundSetting;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DokodemoDoorInboundSetting extends InboundSetting {

    private String network;
    private String address;
}
