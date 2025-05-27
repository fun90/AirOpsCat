package com.fun90.airopscat.model.dto.xray.setting;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fun90.airopscat.model.dto.xray.setting.stream.*;
import lombok.Data;

// 流设置
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamSetting {
    private String network;
    private String security;
    private RealitySettings realitySettings;
    private TcpSettings tcpSettings;
    private WebSocketSettings wsSettings;
    private HttpSettings httpSettings;
    private GrpcSettings grpcSettings;
}