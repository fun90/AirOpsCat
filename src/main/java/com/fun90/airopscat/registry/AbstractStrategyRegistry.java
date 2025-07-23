package com.fun90.airopscat.registry;

import com.fun90.airopscat.util.CdiAnnotationUtils;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.inject.Instance;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略注册表抽象基类
 * 提供通用的策略注册、查找和管理功能
 * 
 * @param <S> 策略接口类型
 * @param <A> 注解类型
 */
@Slf4j
public abstract class AbstractStrategyRegistry<S, A extends Annotation> {
    
    protected final Map<String, S> strategies = new ConcurrentHashMap<>();
    protected final Map<String, A> strategyMetadata = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        List<S> strategyList = new ArrayList<>();
        getStrategyInstances().forEach(strategyList::add);
        
        log.info("初始化{}，发现 {} 个策略", getRegistryName(), strategyList.size());
        
        // 按优先级排序策略
        List<S> sortedStrategies = strategyList.stream()
                .sorted(this::compareStrategyPriority)
                .toList();
        
        for (S strategy : sortedStrategies) {
            registerStrategy(strategy);
        }
        
        log.info("{}：注册了 {} 个策略，支持 {} 种类型",
                getRegistryName(), strategies.size(), getRegisteredTypes().size());
    }
    
    /**
     * 注册策略
     */
    public void registerStrategy(S strategy) {
        A annotation = findAnnotation(strategy);
        if (annotation == null) {
            log.warn("策略 {} 没有 @{} 注解，跳过注册", 
                    strategy.getClass().getSimpleName(), getAnnotationClass().getSimpleName());
            return;
        }
        
        for (String type : getSupportedTypes(annotation)) {
            String normalizedType = type.toLowerCase().trim();
            
            // 检查是否已存在更高优先级的策略
            if (strategies.containsKey(normalizedType)) {
                A existingAnnotation = strategyMetadata.get(normalizedType);
                if (getPriority(existingAnnotation) <= getPriority(annotation)) {
                    log.debug("跳过注册策略 {} (类型: {})，存在更高优先级策略", 
                            getStrategyName(strategy), normalizedType);
                    continue;
                }
            }
            
            strategies.put(normalizedType, strategy);
            strategyMetadata.put(normalizedType, annotation);
            
            log.debug("注册策略 {} 支持类型 {} (优先级: {})", 
                    getStrategyName(strategy), normalizedType, getPriority(annotation));
        }
    }
    
    /**
     * 获取指定类型的策略
     */
    public S getStrategy(String type) {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException(getTypeErrorMessage());
        }
        
        S strategy = strategies.get(type.toLowerCase().trim());
        if (strategy == null) {
            throw new UnsupportedOperationException(
                String.format("不支持的类型: %s。支持的类型: %s", 
                        type, getRegisteredTypes()));
        }
        
        return strategy;
    }

    /**
     * 获取所有已注册的类型
     */
    public Set<String> getRegisteredTypes() {
        return new HashSet<>(strategies.keySet());
    }

    /**
     * 比较策略优先级
     */
    private int compareStrategyPriority(S s1, S s2) {
        A a1 = findAnnotation(s1);
        A a2 = findAnnotation(s2);
        
        if (a1 == null && a2 == null) return 0;
        if (a1 == null) return 1;
        if (a2 == null) return -1;
        
        return Integer.compare(getPriority(a1), getPriority(a2));
    }
    
    /**
     * 查找策略注解
     */
    protected A findAnnotation(S strategy) {
        return CdiAnnotationUtils.findAnnotation(strategy, getAnnotationClass());
    }
    
    // 抽象方法，子类必须实现
    
    /**
     * 获取策略实例
     */
    protected abstract Instance<S> getStrategyInstances();
    
    /**
     * 获取注解类型
     */
    protected abstract Class<A> getAnnotationClass();
    
    /**
     * 获取注册表名称
     */
    protected abstract String getRegistryName();
    
    /**
     * 获取策略名称
     */
    protected abstract String getStrategyName(S strategy);
    
    /**
     * 从注解中获取支持的类型列表
     */
    protected abstract String[] getSupportedTypes(A annotation);
    
    /**
     * 从注解中获取优先级
     */
    protected abstract int getPriority(A annotation);
    
    /**
     * 获取类型错误消息
     */
    protected abstract String getTypeErrorMessage();

}