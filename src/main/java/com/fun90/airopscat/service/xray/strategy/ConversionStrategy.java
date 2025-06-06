package com.fun90.airopscat.service.xray.strategy;

import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.OutboundConfig;

/**
 * 配置转换策略接口
 */
public interface ConversionStrategy {
    
    /**
     * 执行转换
     * @param inbound 入站配置
     * @param serverAddress 服务器地址
     * @param serverPort 服务器端口
     * @return 出站配置
     */
    OutboundConfig convert(InboundConfig inbound, String serverAddress, Integer serverPort);
    
    /**
     * 验证配置是否有效
     * @param inbound 入站配置
     * @return 验证结果
     */
    default boolean validate(InboundConfig inbound) {
        return inbound != null && inbound.getProtocol() != null;
    }
    
    /**
     * 获取策略名称
     * @return 策略名称
     */
    default String getStrategyName() {
        return this.getClass().getSimpleName();
    }
}
