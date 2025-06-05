package com.fun90.airopscat.service.converter;

import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.OutboundConfig;
import com.fun90.airopscat.model.dto.xray.setting.inbound.ShadowsocksInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.outbound.ShadowsocksOutboundSetting;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ShadowsocksConversionStrategy implements ConversionStrategy {
    @Override
    public OutboundConfig convert(InboundConfig inboundConfig, String serverAddress, Integer serverPort) {
        if (!"shadowsocks".equalsIgnoreCase(inboundConfig.getProtocol())) {
            throw new IllegalArgumentException("Expected shadowsocks protocol, got: " + inboundConfig.getProtocol());
        }

        OutboundConfig outboundConfig = new OutboundConfig();
        outboundConfig.setProtocol("shadowsocks");

        // 转换设置
        ShadowsocksInboundSetting inboundSetting = (ShadowsocksInboundSetting) inboundConfig.getSettings();
        if (inboundSetting != null) {
            ShadowsocksOutboundSetting outboundSetting = new ShadowsocksOutboundSetting();

            List<ShadowsocksOutboundSetting.ShadowsocksServer> servers = new ArrayList<>();

            // 处理客户端配置
            if (inboundSetting.getClients() != null && !inboundSetting.getClients().isEmpty()) {
                // 如果有多个客户端，为每个客户端创建一个服务器配置
                for (ShadowsocksInboundSetting.ShadowsocksClient client : inboundSetting.getClients()) {
                    ShadowsocksOutboundSetting.ShadowsocksServer server =
                            new ShadowsocksOutboundSetting.ShadowsocksServer();
                    server.setAddress(serverAddress);
                    server.setPort(serverPort);
                    server.setMethod(client.getMethod());
                    server.setPassword(client.getPassword());
                    server.setEmail(client.getEmail());
                    servers.add(server);
                }
            } else {
                // 如果没有客户端配置，使用全局配置
                ShadowsocksOutboundSetting.ShadowsocksServer server =
                        new ShadowsocksOutboundSetting.ShadowsocksServer();
                server.setAddress(serverAddress);
                server.setPort(serverPort);
                server.setMethod(inboundSetting.getMethod());
                server.setPassword(inboundSetting.getPassword());
                servers.add(server);
            }

            outboundSetting.setServers(servers);
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
