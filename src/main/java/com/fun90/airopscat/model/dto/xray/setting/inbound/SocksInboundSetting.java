package com.fun90.airopscat.model.dto.xray.setting.inbound;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fun90.airopscat.model.dto.xray.setting.InboundSetting;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SocksInboundSetting extends InboundSetting {
    private String auth;
    private List<SocksAccount> accounts;
    private Boolean udp;
    private String ip;
    private Integer userLevel;
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SocksAccount {
        private String user;
        private String pass;
    }
}