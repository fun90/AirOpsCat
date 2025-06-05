package com.fun90.airopscat.service.converter;

import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.OutboundConfig;
import com.fun90.airopscat.model.dto.xray.setting.inbound.SocksInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.VlessInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.outbound.SocksOutboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.outbound.VlessOutboundSetting;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class VlessConversionStrategy implements ConversionStrategy {
    @Override
    public OutboundConfig convert(InboundConfig inboundConfig, String serverAddress, Integer serverPort) {
        if (!"vless".equalsIgnoreCase(inboundConfig.getProtocol())) {
            throw new IllegalArgumentException("Expected vless protocol, got: " + inboundConfig.getProtocol());
        }

        OutboundConfig outboundConfig = new OutboundConfig();
        outboundConfig.setProtocol("vless");

        // 转换设置
        VlessInboundSetting inboundSetting = (VlessInboundSetting) inboundConfig.getSettings();
        if (inboundSetting != null) {
            VlessOutboundSetting outboundSetting = new VlessOutboundSetting();

            // 创建服务器配置
            List<VlessOutboundSetting.VlessServer> vnext = new ArrayList<>();
            VlessOutboundSetting.VlessServer server = new VlessOutboundSetting.VlessServer();
            server.setAddress(serverAddress);
            server.setPort(serverPort);

            // 转换用户信息
            if (inboundSetting.getClients() != null && !inboundSetting.getClients().isEmpty()) {
                List<VlessOutboundSetting.VlessUser> users = new ArrayList<>();
                for (VlessInboundSetting.VlessClient client : inboundSetting.getClients()) {
                    VlessOutboundSetting.VlessUser user = new VlessOutboundSetting.VlessUser();
                    user.setId(client.getId());
                    user.setEncryption("none"); // VLESS 通常使用 none 加密
                    user.setFlow(client.getFlow());
                    user.setLevel(client.getLevel());
                    users.add(user);
                }
                server.setUsers(users);
            }

            vnext.add(server);
            outboundSetting.setVnext(vnext);
            outboundConfig.setSettings(outboundSetting);
        }

        // 复制流设置
        if (inboundConfig.getStreamSettings() != null) {
            outboundConfig.setStreamSettings(inboundConfig.getStreamSettings());
        }

        return outboundConfig;
    }
    
    @Override
    public boolean supports(String protocol) {
        return "socks".equalsIgnoreCase(protocol);
    }
}
