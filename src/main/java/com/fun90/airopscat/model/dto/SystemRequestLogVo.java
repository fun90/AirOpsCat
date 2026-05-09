package com.fun90.airopscat.model.dto;

import com.fun90.airopscat.model.entity.SystemRequestLog;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SystemRequestLogVo {
    private Long id;
    private LocalDateTime accessTime;
    private String method;
    private String requestPath;
    private String queryString;
    private String clientIp;
    private Integer statusCode;
    private Long durationMillis;
    private String userAgent;
    private String referer;

    public static SystemRequestLogVo from(SystemRequestLog log) {
        if (log == null) {
            return null;
        }
        SystemRequestLogVo vo = new SystemRequestLogVo();
        vo.id = log.getId();
        vo.accessTime = log.getAccessTime();
        vo.method = log.getMethod();
        vo.requestPath = log.getRequestPath();
        vo.queryString = log.getQueryString();
        vo.clientIp = log.getClientIp();
        vo.statusCode = log.getStatusCode();
        vo.durationMillis = log.getDurationMillis();
        vo.userAgent = log.getUserAgent();
        vo.referer = log.getReferer();
        return vo;
    }
}
