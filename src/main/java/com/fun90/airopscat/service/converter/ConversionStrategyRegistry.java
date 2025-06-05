package com.fun90.airopscat.service.converter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConversionStrategyRegistry {
    
    private final Map<String, ConversionStrategy> strategies = new ConcurrentHashMap<>();
    
    @Autowired
    public ConversionStrategyRegistry(List<ConversionStrategy> strategyList) {
        for (ConversionStrategy strategy : strategyList) {
            registerStrategy(strategy);
        }
    }
    
    public void registerStrategy(ConversionStrategy strategy) {
        // 动态注册策略，支持运行时添加
        for (String protocol : getSupportedProtocols(strategy)) {
            strategies.put(protocol.toLowerCase(), strategy);
        }
    }
    
    public ConversionStrategy getStrategy(String protocol) {
        ConversionStrategy strategy = strategies.get(protocol.toLowerCase());
        if (strategy == null) {
            throw new UnsupportedOperationException("No conversion strategy found for protocol: " + protocol);
        }
        return strategy;
    }
    
    private String[] getSupportedProtocols(ConversionStrategy strategy) {
        // 通过反射或注解获取支持的协议
        if (strategy instanceof SocksConversionStrategy) return new String[]{"socks"};
        if (strategy instanceof ShadowsocksConversionStrategy) return new String[]{"shadowsocks"};
        if (strategy instanceof VlessConversionStrategy) return new String[]{"vless"};
        return new String[]{};
    }
}