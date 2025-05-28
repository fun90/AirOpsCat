package com.fun90.airopscat.model.dto.xray.setting.outbound;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fun90.airopscat.model.dto.xray.setting.OutboundSetting;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BlackholeOutboundSetting extends OutboundSetting {
    // Blackhole协议设置
    private BlackholeResponse response;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BlackholeResponse {
        private String type;
    }
}
