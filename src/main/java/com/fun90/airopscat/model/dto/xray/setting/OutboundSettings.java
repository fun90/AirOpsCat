package com.fun90.airopscat.model.dto.xray.setting;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fun90.airopscat.model.dto.xray.setting.inbound.Hysteria2InboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.ShadowsocksInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.SocksInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.VlessInboundSetting;
import com.fun90.airopscat.model.enums.XrayProtocolType;
import lombok.Data;

// 出站设置
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "protocolType")
@JsonSubTypes(value = {
        @JsonSubTypes.Type(value = VlessInboundSetting.class, name = "VLESS"),
        @JsonSubTypes.Type(value = Hysteria2InboundSetting.class, name = "HYSTERIA2"),
        @JsonSubTypes.Type(value = SocksInboundSetting.class, name = "SOCKS"),
        @JsonSubTypes.Type(value = ShadowsocksInboundSetting.class, name = "SHADOWSOCKS"),
})
public class OutboundSettings {
    private XrayProtocolType protocolType;
}