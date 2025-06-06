package com.fun90.airopscat.service.xray.strategy.impl;

import com.fun90.airopscat.annotation.SupportedProtocols;
import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.OutboundConfig;
import com.fun90.airopscat.model.dto.xray.setting.inbound.ShadowsocksInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.outbound.ShadowsocksOutboundSetting;
import com.fun90.airopscat.service.xray.strategy.ConversionStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@SupportedProtocols(value = {"shadowsocks", "ss"}, priority = 1, description = "Shadowsocks协议转换器")
public class ShadowsocksConversionStrategy implements ConversionStrategy {
    
    @Override
    public OutboundConfig convert(InboundConfig inbound, String serverAddress, Integer serverPort) {
        log.debug("Converting Shadowsocks inbound config to outbound, server: {}:{}", serverAddress, serverPort);
        
        if (!validate(inbound)) {
            throw new IllegalArgumentException("Invalid Shadowsocks inbound configuration");
        }
        
        OutboundConfig outbound = new OutboundConfig();
        outbound.setProtocol("shadowsocks");
        outbound.setTag("ss-out-" + System.currentTimeMillis());
        
        ShadowsocksInboundSetting inboundSetting = (ShadowsocksInboundSetting) inbound.getSettings();
        if (inboundSetting != null) {
            ShadowsocksOutboundSetting outboundSetting = createShadowsocksOutboundSetting(
                inboundSetting, serverAddress, serverPort);
            outbound.setSettings(outboundSetting);
        }
        
        // 复制流设置
        if (inbound.getStreamSettings() != null) {
            outbound.setStreamSettings(inbound.getStreamSettings());
        }
        
        log.debug("Shadowsocks conversion completed successfully");
        return outbound;
    }
    
    @Override
    public boolean validate(InboundConfig inbound) {
        if (!ConversionStrategy.super.validate(inbound)) {
            return false;
        }
        
        String protocol = inbound.getProtocol().toLowerCase();
        return "shadowsocks".equals(protocol) || "ss".equals(protocol);
    }
    
    private ShadowsocksOutboundSetting createShadowsocksOutboundSetting(ShadowsocksInboundSetting inbound,
                                                                       String serverAddress, Integer serverPort) {
        ShadowsocksOutboundSetting outbound = new ShadowsocksOutboundSetting();
        
        List<ShadowsocksOutboundSetting.ShadowsocksServer> servers = new ArrayList<>();
        
        // 处理客户端配置
        if (inbound.getClients() != null && !inbound.getClients().isEmpty()) {
            // 为每个客户端创建服务器配置
            for (ShadowsocksInboundSetting.ShadowsocksClient client : inbound.getClients()) {
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
            // 使用全局配置
            ShadowsocksOutboundSetting.ShadowsocksServer server = 
                new ShadowsocksOutboundSetting.ShadowsocksServer();
            server.setAddress(serverAddress);
            server.setPort(serverPort);
            server.setMethod(inbound.getMethod());
            server.setPassword(inbound.getPassword());
            servers.add(server);
        }
        
        outbound.setServers(servers);
        return outbound;
    }
}