package com.fun90.airopscat.service.inbound.registry;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.registry.AbstractStrategyRegistry;
import com.fun90.airopscat.service.inbound.strategy.DefaultInboundStrategy;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class DefaultInboundStrategyRegistry extends AbstractStrategyRegistry<DefaultInboundStrategy, SupportedCores> {

    @Inject
    Instance<DefaultInboundStrategy> strategyInstances;

    @Override
    protected Instance<DefaultInboundStrategy> getStrategyInstances() {
        return strategyInstances;
    }

    @Override
    protected Class<SupportedCores> getAnnotationClass() {
        return SupportedCores.class;
    }

    @Override
    protected String getRegistryName() {
        return "默认入站配置策略注册表";
    }

    @Override
    protected String getStrategyName(DefaultInboundStrategy strategy) {
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
