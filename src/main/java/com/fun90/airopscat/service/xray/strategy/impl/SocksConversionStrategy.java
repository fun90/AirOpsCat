package com.fun90.airopscat.service.xray.strategy.impl;

import com.fun90.airopscat.annotation.SupportedProtocols;
import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.OutboundConfig;
import com.fun90.airopscat.model.dto.xray.setting.inbound.SocksInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.outbound.SocksOutboundSetting;
import com.fun90.airopscat.service.xray.strategy.ConversionStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@SupportedProtocols(value = {"socks", "socks5"}, priority = 1, description = "Socks协议转换器")
public class SocksConversionStrategy implements ConversionStrategy {
    
    @Override
    public OutboundConfig convert(InboundConfig inbound, String serverAddress, Integer serverPort) {
        log.debug("Converting Socks inbound config to outbound, server: {}:{}", serverAddress, serverPort);
        
        if (!validate(inbound)) {
            throw new IllegalArgumentException("Invalid Socks inbound configuration");
        }
        
        OutboundConfig outbound = new OutboundConfig();
        outbound.setProtocol("socks");
        outbound.setTag("socks-out-" + System.currentTimeMillis());
        
        SocksInboundSetting inboundSetting = (SocksInboundSetting) inbound.getSettings();
        if (inboundSetting != null) {
            SocksOutboundSetting outboundSetting = createSocksOutboundSetting(
                inboundSetting, serverAddress, serverPort);
            outbound.setSettings(outboundSetting);
        }
        
        // 复制流设置
        if (inbound.getStreamSettings() != null) {
            outbound.setStreamSettings(inbound.getStreamSettings());
        }
        
        log.debug("Socks conversion completed successfully");
        return outbound;
    }
    
    @Override
    public boolean validate(InboundConfig inbound) {
        if (!ConversionStrategy.super.validate(inbound)) {
            return false;
        }
        
        String protocol = inbound.getProtocol().toLowerCase();
        return "socks".equals(protocol) || "socks5".equals(protocol);
    }
    
    private SocksOutboundSetting createSocksOutboundSetting(SocksInboundSetting inbound, 
                                                           String serverAddress, Integer serverPort) {
        SocksOutboundSetting outbound = new SocksOutboundSetting();
        
        List<SocksOutboundSetting.SocksServer> servers = new ArrayList<>();
        SocksOutboundSetting.SocksServer server = new SocksOutboundSetting.SocksServer();
        server.setAddress(serverAddress);
        server.setPort(serverPort);
        
        // 转换用户认证信息
        if (inbound.getAccounts() != null && !inbound.getAccounts().isEmpty()) {
            List<SocksOutboundSetting.SocksUser> users = new ArrayList<>();
            for (SocksInboundSetting.SocksAccount account : inbound.getAccounts()) {
                SocksOutboundSetting.SocksUser user = new SocksOutboundSetting.SocksUser();
                user.setUser(account.getUser());
                user.setPass(account.getPass());
                user.setLevel(0); // 默认级别
                users.add(user);
            }
            server.setUsers(users);
        }
        
        servers.add(server);
        outbound.setServers(servers);
        
        return outbound;
    }
}