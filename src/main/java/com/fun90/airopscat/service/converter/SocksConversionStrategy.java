package com.fun90.airopscat.service.converter;

import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.OutboundConfig;
import com.fun90.airopscat.model.dto.xray.setting.inbound.SocksInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.outbound.SocksOutboundSetting;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SocksConversionStrategy implements ConversionStrategy {
    @Override
    public OutboundConfig convert(InboundConfig inboundConfig, String serverAddress, Integer serverPort) {
        if (!"socks".equalsIgnoreCase(inboundConfig.getProtocol())) {
            throw new IllegalArgumentException("Expected socks protocol, got: " + inboundConfig.getProtocol());
        }

        OutboundConfig outboundConfig = new OutboundConfig();
        outboundConfig.setProtocol("socks");

        // 转换设置
        SocksInboundSetting inboundSetting = (SocksInboundSetting) inboundConfig.getSettings();
        if (inboundSetting != null) {
            SocksOutboundSetting outboundSetting = new SocksOutboundSetting();

            // 创建服务器配置
            List<SocksOutboundSetting.SocksServer> servers = new ArrayList<>();
            SocksOutboundSetting.SocksServer server = new SocksOutboundSetting.SocksServer();
            server.setAddress(serverAddress);
            server.setPort(serverPort);

            // 转换用户认证信息
            if (inboundSetting.getAccounts() != null && !inboundSetting.getAccounts().isEmpty()) {
                List<SocksOutboundSetting.SocksUser> users = new ArrayList<>();
                for (SocksInboundSetting.SocksAccount account : inboundSetting.getAccounts()) {
                    SocksOutboundSetting.SocksUser user = new SocksOutboundSetting.SocksUser();
                    user.setUser(account.getUser());
                    user.setPass(account.getPass());
                    // 可以添加级别信息
                    user.setLevel(0); // 默认级别
                    users.add(user);
                }
                server.setUsers(users);
            }

            servers.add(server);
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
