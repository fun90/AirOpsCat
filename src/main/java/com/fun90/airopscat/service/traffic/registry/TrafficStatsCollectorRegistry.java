package com.fun90.airopscat.service.traffic.registry;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.registry.AbstractStrategyRegistry;
import com.fun90.airopscat.service.traffic.TrafficStatsCollector;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class TrafficStatsCollectorRegistry extends AbstractStrategyRegistry<TrafficStatsCollector, SupportedCores> {

    @Inject
    Instance<TrafficStatsCollector> strategyInstances;

    @Override
    protected Instance<TrafficStatsCollector> getStrategyInstances() {
        return strategyInstances;
    }

    @Override
    protected Class<SupportedCores> getAnnotationClass() {
        return SupportedCores.class;
    }

    @Override
    protected String getRegistryName() {
        return "流量统计采集策略注册表";
    }

    @Override
    protected String getStrategyName(TrafficStatsCollector strategy) {
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
        return "流量统计内核类型不能为空";
    }
}
