package com.fun90.airopscat.model.dto.xray;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

// 日志配置
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LogConfig {
    private String loglevel;
    private Boolean dnsLog;
    private String error;
    private String access;
}
