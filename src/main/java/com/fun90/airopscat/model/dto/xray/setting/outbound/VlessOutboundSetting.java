package com.fun90.airopscat.model.dto.xray.setting.outbound;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fun90.airopscat.model.dto.xray.setting.OutboundSetting;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VlessOutboundSetting extends OutboundSetting {
    private List<VlessServer> vnext;
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VlessServer {
        private String address;
        private Integer port;
        private List<VlessUser> users;
    }
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VlessUser {
        private String id;
        private String encryption;
        private String flow;
        private Integer level;
    }
}
