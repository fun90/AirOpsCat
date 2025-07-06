package com.fun90.airopscat.model.dto.xray.setting.inbound;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fun90.airopscat.model.dto.xray.setting.InboundSetting;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShadowsocksInboundSetting extends InboundSetting {
    private String method;
    private String password;
    private List<ShadowsocksClient> clients;
    private String network;
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ShadowsocksClient {
        private String password;
        private String method;
        private String email;
    }
}