package com.fun90.airopscat.service.xray.registry;

import com.fun90.airopscat.annotation.SupportedProtocols;
import com.fun90.airopscat.registry.AbstractStrategyRegistry;
import com.fun90.airopscat.service.xray.strategy.ConversionStrategy;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * 转换策略注册表
 */
@Startup
@ApplicationScoped
public class ConversionStrategyRegistry extends AbstractStrategyRegistry<ConversionStrategy, SupportedProtocols> {
    
    @Inject
    Instance<ConversionStrategy> strategyInstances;
    
    @Override
    protected Instance<ConversionStrategy> getStrategyInstances() {
        return strategyInstances;
    }
    
    @Override
    protected Class<SupportedProtocols> getAnnotationClass() {
        return SupportedProtocols.class;
    }
    
    @Override
    protected String getRegistryName() {
        return "转换策略注册表";
    }
    
    @Override
    protected String getStrategyName(ConversionStrategy strategy) {
        return strategy.getStrategyName();
    }
    
    @Override
    protected String[] getSupportedTypes(SupportedProtocols annotation) {
        return annotation.value();
    }
    
    @Override
    protected int getPriority(SupportedProtocols annotation) {
        return annotation.priority();
    }
    
    @Override
    protected String getTypeErrorMessage() {
        return "Protocol cannot be null or empty";
    }
}