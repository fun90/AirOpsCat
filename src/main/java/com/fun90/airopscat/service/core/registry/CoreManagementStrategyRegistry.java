package com.fun90.airopscat.service.core.registry;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.registry.AbstractStrategyRegistry;
import com.fun90.airopscat.service.core.strategy.CoreManagementStrategy;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Arrays;

/**
 * 内核管理策略注册表
 */
@Startup
@ApplicationScoped
public class CoreManagementStrategyRegistry extends AbstractStrategyRegistry<CoreManagementStrategy, SupportedCores> {
    
    @Inject
    Instance<CoreManagementStrategy> strategyInstances;
    
    @Override
    protected Instance<CoreManagementStrategy> getStrategyInstances() {
        return strategyInstances;
    }
    
    @Override
    protected Class<SupportedCores> getAnnotationClass() {
        return SupportedCores.class;
    }
    
    @Override
    protected String getRegistryName() {
        return "内核管理策略注册表";
    }
    
    @Override
    protected String getStrategyName(CoreManagementStrategy strategy) {
        return strategy.getStrategyName();
    }
    
    @Override
    protected String[] getSupportedTypes(SupportedCores annotation) {
        return annotation.value();
    }
    
    @Override
    protected int getPriority(SupportedCores annotation) {
        return annotation.priority();
    }
    
    @Override
    protected String getTypeErrorMessage() {
        return "内核类型不能为空";
    }
    
    @Override
    protected String formatStrategyDescription(CoreManagementStrategy strategy, SupportedCores annotation) {
        return String.format("%s (优先级: %d, 描述: %s, 支持系统: %s)", 
                strategy.getStrategyName(), 
                annotation.priority(), 
                annotation.description(),
                Arrays.toString(annotation.supportedOS()));
    }
}
