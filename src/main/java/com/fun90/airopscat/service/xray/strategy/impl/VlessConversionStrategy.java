package com.fun90.airopscat.service.xray.strategy.impl;

import com.fun90.airopscat.annotation.SupportedProtocols;
import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.OutboundConfig;
import com.fun90.airopscat.model.dto.xray.setting.inbound.VlessInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.outbound.VlessOutboundSetting;
import com.fun90.airopscat.service.xray.strategy.ConversionStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@SupportedProtocols(value = {"vless"}, priority = 1, description = "VLESS协议转换器")
public class VlessConversionStrategy implements ConversionStrategy {
    
    @Override
    public OutboundConfig convert(InboundConfig inbound, String serverAddress, Integer serverPort) {
        log.debug("Converting VLESS inbound config to outbound, server: {}:{}", serverAddress, serverPort);
        
        if (!validate(inbound)) {
            throw new IllegalArgumentException("Invalid VLESS inbound configuration");
        }
        
        OutboundConfig outbound = new OutboundConfig();
        outbound.setProtocol("vless");
        outbound.setTag("vless-out-" + System.currentTimeMillis());
        
        VlessInboundSetting inboundSetting = (VlessInboundSetting) inbound.getSettings();
        if (inboundSetting != null) {
            VlessOutboundSetting outboundSetting = createVlessOutboundSetting(
                inboundSetting, serverAddress, serverPort);
            outbound.setSettings(outboundSetting);
        }
        
        // 复制流设置
        if (inbound.getStreamSettings() != null) {
            outbound.setStreamSettings(inbound.getStreamSettings());
        }
        
        log.debug("VLESS conversion completed successfully");
        return outbound;
    }
    
    @Override
    public boolean validate(InboundConfig inbound) {
        if (!ConversionStrategy.super.validate(inbound)) {
            return false;
        }
        
        return "vless".equalsIgnoreCase(inbound.getProtocol());
    }
    
    private VlessOutboundSetting createVlessOutboundSetting(VlessInboundSetting inbound,
                                                           String serverAddress, Integer serverPort) {
        VlessOutboundSetting outbound = new VlessOutboundSetting();
        
        List<VlessOutboundSetting.VlessServer> vnext = new ArrayList<>();
        VlessOutboundSetting.VlessServer server = new VlessOutboundSetting.VlessServer();
        server.setAddress(serverAddress);
        server.setPort(serverPort);
        
        // 转换用户信息
        if (inbound.getClients() != null && !inbound.getClients().isEmpty()) {
            List<VlessOutboundSetting.VlessUser> users = new ArrayList<>();
            for (VlessInboundSetting.VlessClient client : inbound.getClients()) {
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
        outbound.setVnext(vnext);
        
        return outbound;
    }
}