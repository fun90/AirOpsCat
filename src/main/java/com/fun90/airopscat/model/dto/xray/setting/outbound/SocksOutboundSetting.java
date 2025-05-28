package com.fun90.airopscat.model.dto.xray.setting.outbound;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fun90.airopscat.model.dto.xray.setting.OutboundSetting;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SocksOutboundSetting extends OutboundSetting {
    // Socks协议设置
    private List<SocksServer> servers;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SocksServer {
        private String address;
        private Integer port;
        private List<SocksUser> users;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SocksUser {
        private String user;
        private String pass;
        private Integer level;
    }
}
