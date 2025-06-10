package com.fun90.airopscat.service.core.registry;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.service.core.strategy.CoreManagementStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内核管理策略注册表
 */
@Slf4j
@Component
public class CoreManagementStrategyRegistry {
    
    private final Map<String, CoreManagementStrategy> strategies = new ConcurrentHashMap<>();
    private final Map<String, SupportedCores> strategyMetadata = new ConcurrentHashMap<>();
    
    @Autowired
    public CoreManagementStrategyRegistry(List<CoreManagementStrategy> strategyList) {
        log.info("初始化内核管理策略注册表，发现 {} 个策略", strategyList.size());
        
        // 按优先级排序策略
        List<CoreManagementStrategy> sortedStrategies = strategyList.stream()
                .sorted(this::compareStrategyPriority)
                .collect(Collectors.toList());
        
        for (CoreManagementStrategy strategy : sortedStrategies) {
            registerStrategy(strategy);
        }
        
        log.info("注册了 {} 个内核管理策略，支持 {} 种内核类型", 
                strategies.size(), getRegisteredCores().size());
    }
    
    /**
     * 注册策略
     */
    public void registerStrategy(CoreManagementStrategy strategy) {
        SupportedCores annotation = strategy.getClass().getAnnotation(SupportedCores.class);
        if (annotation == null) {
            log.warn("策略 {} 没有 @SupportedCores 注解，跳过注册", 
                    strategy.getClass().getSimpleName());
            return;
        }
        
        for (String coreType : annotation.value()) {
            String normalizedCoreType = coreType.toLowerCase().trim();
            
            // 检查是否已存在更高优先级的策略
            if (strategies.containsKey(normalizedCoreType)) {
                SupportedCores existingAnnotation = strategyMetadata.get(normalizedCoreType);
                if (existingAnnotation.priority() <= annotation.priority()) {
                    log.debug("跳过注册策略 {} (内核: {})，存在更高优先级策略", 
                            strategy.getStrategyName(), normalizedCoreType);
                    continue;
                }
            }
            
            strategies.put(normalizedCoreType, strategy);
            strategyMetadata.put(normalizedCoreType, annotation);
            
            log.debug("注册策略 {} 支持内核 {} (优先级: {})", 
                    strategy.getStrategyName(), normalizedCoreType, annotation.priority());
        }
    }
    
    /**
     * 获取指定内核类型的管理策略
     */
    public CoreManagementStrategy getStrategy(String coreType) {
        if (coreType == null || coreType.trim().isEmpty()) {
            throw new IllegalArgumentException("内核类型不能为空");
        }
        
        CoreManagementStrategy strategy = strategies.get(coreType.toLowerCase().trim());
        if (strategy == null) {
            throw new UnsupportedOperationException(
                String.format("不支持的内核类型: %s。支持的内核类型: %s", 
                        coreType, getRegisteredCores()));
        }
        
        return strategy;
    }
    
    /**
     * 检查是否支持指定内核类型
     */
    public boolean isSupported(String coreType) {
        if (coreType == null || coreType.trim().isEmpty()) {
            return false;
        }
        return strategies.containsKey(coreType.toLowerCase().trim());
    }
    
    /**
     * 获取所有已注册的内核类型
     */
    public Set<String> getRegisteredCores() {
        return new HashSet<>(strategies.keySet());
    }
    
    /**
     * 获取策略信息
     */
    public Map<String, String> getStrategyInfo() {
        Map<String, String> info = new HashMap<>();
        for (Map.Entry<String, CoreManagementStrategy> entry : strategies.entrySet()) {
            String coreType = entry.getKey();
            CoreManagementStrategy strategy = entry.getValue();
            SupportedCores annotation = strategyMetadata.get(coreType);
            
            String description = String.format("%s (优先级: %d, 描述: %s, 支持系统: %s)", 
                    strategy.getStrategyName(), 
                    annotation.priority(), 
                    annotation.description(),
                    Arrays.toString(annotation.supportedOS()));
            info.put(coreType, description);
        }
        return info;
    }
    
    /**
     * 获取指定内核类型的策略元数据
     */
    public Optional<SupportedCores> getStrategyMetadata(String coreType) {
        if (coreType == null || coreType.trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(strategyMetadata.get(coreType.toLowerCase().trim()));
    }
    
    /**
     * 根据操作系统过滤支持的内核类型
     */
    public Set<String> getSupportedCoresByOS(String osType) {
        return strategyMetadata.entrySet().stream()
                .filter(entry -> Arrays.asList(entry.getValue().supportedOS()).contains(osType.toLowerCase()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
    
    /**
     * 比较策略优先级
     */
    private int compareStrategyPriority(CoreManagementStrategy s1, CoreManagementStrategy s2) {
        SupportedCores a1 = s1.getClass().getAnnotation(SupportedCores.class);
        SupportedCores a2 = s2.getClass().getAnnotation(SupportedCores.class);
        
        if (a1 == null && a2 == null) return 0;
        if (a1 == null) return 1;
        if (a2 == null) return -1;
        
        return Integer.compare(a1.priority(), a2.priority());
    }
}
