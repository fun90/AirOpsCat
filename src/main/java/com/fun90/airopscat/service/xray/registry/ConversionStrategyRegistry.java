package com.fun90.airopscat.service.xray.registry;

import com.fun90.airopscat.annotation.SupportedProtocols;
import com.fun90.airopscat.service.xray.strategy.ConversionStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 转换策略注册表
 */
@Slf4j
@Component
public class ConversionStrategyRegistry {
    
    private final Map<String, ConversionStrategy> strategies = new ConcurrentHashMap<>();
    private final Map<String, SupportedProtocols> strategyMetadata = new ConcurrentHashMap<>();
    
    @Autowired
    public ConversionStrategyRegistry(List<ConversionStrategy> strategyList) {
        log.info("Initializing ConversionStrategyRegistry with {} strategies", strategyList.size());
        
        // 按优先级排序策略
        List<ConversionStrategy> sortedStrategies = strategyList.stream()
                .sorted(this::compareStrategyPriority)
                .collect(Collectors.toList());
        
        for (ConversionStrategy strategy : sortedStrategies) {
            registerStrategy(strategy);
        }
        
        log.info("Registered {} conversion strategies for {} protocols", 
                strategies.size(), getRegisteredProtocols().size());
    }
    
    /**
     * 注册策略
     */
    public void registerStrategy(ConversionStrategy strategy) {
        SupportedProtocols annotation = strategy.getClass().getAnnotation(SupportedProtocols.class);
        if (annotation == null) {
            log.warn("Strategy {} has no @SupportedProtocols annotation, skipping registration", 
                    strategy.getClass().getSimpleName());
            return;
        }
        
        for (String protocol : annotation.value()) {
            String normalizedProtocol = protocol.toLowerCase().trim();
            
            // 检查是否已存在更高优先级的策略
            if (strategies.containsKey(normalizedProtocol)) {
                SupportedProtocols existingAnnotation = strategyMetadata.get(normalizedProtocol);
                if (existingAnnotation.priority() <= annotation.priority()) {
                    log.debug("Skipping registration of {} for protocol {} due to lower priority", 
                            strategy.getStrategyName(), normalizedProtocol);
                    continue;
                }
            }
            
            strategies.put(normalizedProtocol, strategy);
            strategyMetadata.put(normalizedProtocol, annotation);
            
            log.debug("Registered strategy {} for protocol {} with priority {}", 
                    strategy.getStrategyName(), normalizedProtocol, annotation.priority());
        }
    }
    
    /**
     * 获取指定协议的转换策略
     */
    public ConversionStrategy getStrategy(String protocol) {
        if (protocol == null || protocol.trim().isEmpty()) {
            throw new IllegalArgumentException("Protocol cannot be null or empty");
        }
        
        ConversionStrategy strategy = strategies.get(protocol.toLowerCase().trim());
        if (strategy == null) {
            throw new UnsupportedOperationException(
                String.format("No conversion strategy found for protocol: %s. Supported protocols: %s", 
                        protocol, getRegisteredProtocols()));
        }
        
        return strategy;
    }
    
    /**
     * 检查是否支持指定协议
     */
    public boolean isSupported(String protocol) {
        if (protocol == null || protocol.trim().isEmpty()) {
            return false;
        }
        return strategies.containsKey(protocol.toLowerCase().trim());
    }
    
    /**
     * 获取所有已注册的协议
     */
    public Set<String> getRegisteredProtocols() {
        return new HashSet<>(strategies.keySet());
    }
    
    /**
     * 获取策略信息
     */
    public Map<String, String> getStrategyInfo() {
        Map<String, String> info = new HashMap<>();
        for (Map.Entry<String, ConversionStrategy> entry : strategies.entrySet()) {
            String protocol = entry.getKey();
            ConversionStrategy strategy = entry.getValue();
            SupportedProtocols annotation = strategyMetadata.get(protocol);
            
            String description = String.format("%s (Priority: %d, Description: %s)", 
                    strategy.getStrategyName(), 
                    annotation.priority(), 
                    annotation.description());
            info.put(protocol, description);
        }
        return info;
    }
    
    /**
     * 获取指定协议的策略元数据
     */
    public Optional<SupportedProtocols> getStrategyMetadata(String protocol) {
        if (protocol == null || protocol.trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(strategyMetadata.get(protocol.toLowerCase().trim()));
    }
    
    /**
     * 比较策略优先级
     */
    private int compareStrategyPriority(ConversionStrategy s1, ConversionStrategy s2) {
        SupportedProtocols a1 = s1.getClass().getAnnotation(SupportedProtocols.class);
        SupportedProtocols a2 = s2.getClass().getAnnotation(SupportedProtocols.class);
        
        if (a1 == null && a2 == null) return 0;
        if (a1 == null) return 1;
        if (a2 == null) return -1;
        
        return Integer.compare(a1.priority(), a2.priority());
    }
}