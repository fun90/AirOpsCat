package com.fun90.airopscat.model.dto.xray.setting.inbound;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fun90.airopscat.model.dto.xray.setting.InboundSetting;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VlessInboundSetting extends InboundSetting {

    private List<VlessClient> clients;
    private Object decryption; // 通常为 "none"
    private List<VlessFallback> fallbacks;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VlessClient {
        private String id;
        private String email;
        private Integer level;
        private String flow;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VlessFallback {
        private String dest;
        private Integer port;
        private String xver;
    }
}
