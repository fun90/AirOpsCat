package com.fun90.airopscat.service.converter;

import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.OutboundConfig;

public interface ConversionStrategy {
    OutboundConfig convert(InboundConfig inbound, String serverAddress, Integer serverPort);
    boolean supports(String protocol);
}