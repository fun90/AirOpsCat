package com.fun90.airopscat.model.dto.xray.setting.outbound;

import com.fun90.airopscat.model.dto.xray.setting.OutboundSettings;

import java.util.List;

public class SocksOutboundSetting extends OutboundSettings {
    // Socks协议设置
    private List<SocksServer> servers;
}
