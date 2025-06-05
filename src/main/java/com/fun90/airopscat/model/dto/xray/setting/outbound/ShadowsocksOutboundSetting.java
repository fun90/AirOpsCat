package com.fun90.airopscat.model.dto.xray.setting.outbound;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fun90.airopscat.model.dto.xray.setting.OutboundSetting;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShadowsocksOutboundSetting extends OutboundSetting {
    private List<ShadowsocksServer> servers;
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ShadowsocksServer {
        private String email;
        private String address;
        private Integer port;
        private String method;
        private String password;
        private Integer level;
    }
}
