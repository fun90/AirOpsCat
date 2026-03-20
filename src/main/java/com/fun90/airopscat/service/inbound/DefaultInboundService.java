package com.fun90.airopscat.service.inbound;

import com.fun90.airopscat.model.dto.DefaultConfigDto;
import com.fun90.airopscat.service.inbound.registry.DefaultInboundStrategyRegistry;
import com.fun90.airopscat.service.inbound.strategy.DefaultInboundStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class DefaultInboundService {

    @Inject
    DefaultInboundStrategyRegistry strategyRegistry;

    public DefaultConfigDto<Map<String, Object>> generateDefaultInbound(String coreType, String protocol) {
        DefaultInboundStrategy strategy = strategyRegistry.getStrategy(coreType);
        return strategy.generateDefaultInbound(protocol);
    }
}
