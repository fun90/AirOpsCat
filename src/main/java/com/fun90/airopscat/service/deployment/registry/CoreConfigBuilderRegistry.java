package com.fun90.airopscat.service.deployment.registry;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.registry.AbstractStrategyRegistry;
import com.fun90.airopscat.service.deployment.strategy.CoreConfigBuilder;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class CoreConfigBuilderRegistry extends AbstractStrategyRegistry<CoreConfigBuilder, SupportedCores> {

    @Inject
    Instance<CoreConfigBuilder> strategyInstances;

    @Override
    protected Instance<CoreConfigBuilder> getStrategyInstances() {
        return strategyInstances;
    }

    @Override
    protected Class<SupportedCores> getAnnotationClass() {
        return SupportedCores.class;
    }

    @Override
    protected String getRegistryName() {
        return "内核配置构建策略注册表";
    }

    @Override
    protected String getStrategyName(CoreConfigBuilder strategy) {
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
}
