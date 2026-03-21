package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
public class ServerDto {
    private Long id;
    private String ip;
    private Integer sshPort;
    private String authType;
    private String auth;
    private String host;
    private String name;
    private LocalDate expireDate;
    private LocalDate bandwidthDate;
    private String supplier;
    private BigDecimal price;
    private BigDecimal multiple;
    private Integer bandwidth;
    private Integer disabled;
    private Integer external;
    private String remark;
    private Map<String, Object> transitConfig = new HashMap<>();
    private Map<String, Object> coreConfig = new HashMap<>();
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Long daysUntilExpiration; // 到期剩余天数
    private Long trafficUploadBytes;
    private Long trafficDownloadBytes;
    private Long trafficTotalBytes;
    private LocalDateTime trafficPeriodStart;
    private LocalDateTime trafficPeriodEnd;

}
