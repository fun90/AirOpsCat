package com.fun90.airopscat.service.inbound.strategy.impl;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.dto.DefaultConfigDto;
import com.fun90.airopscat.model.enums.CoreType;
import com.fun90.airopscat.service.inbound.strategy.DefaultInboundStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
@SupportedCores(value = {"sing-box"}, priority = 1, description = "Sing-box 默认入站配置生成策略")
public class SingBoxDefaultInboundStrategy implements DefaultInboundStrategy {

    @Override
    public DefaultConfigDto<Map<String, Object>> generateDefaultInbound(String protocol) {
        throw new UnsupportedOperationException(
                String.format("默认入站配置暂不支持 sing-box 内核，协议: %s", protocol));
    }

    @Override
    public String getStrategyName() {
        return CoreType.SING_BOX.getValue();
    }
}
