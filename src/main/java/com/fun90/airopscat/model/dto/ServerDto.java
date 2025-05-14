package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
    private String supplier;
    private BigDecimal price;
    private BigDecimal multiple;
    private Integer disabled;
    private String remark;
    private Map<String, Object> transitConfig = new HashMap<>();
    private Map<String, Object> coreConfig = new HashMap<>();
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Long daysUntilExpiration; // 到期剩余天数
    private String statusType; // 状态类型：active, disabled, expired
    
    // 辅助方法：获取到期状态描述
    public String getStatusDescription() {
        if (disabled != null && disabled == 1) {
            return "已禁用";
        }
        
        if (expireDate == null) {
            return "永不过期";
        }
        
        long days = ChronoUnit.DAYS.between(LocalDate.now(), expireDate);
        if (days < 0) {
            return "已过期 " + Math.abs(days) + " 天";
        } else if (days == 0) {
            return "今天过期";
        } else if (days <= 7) {
            return days + " 天后过期";
        } else {
            return "正常 (还有 " + days + " 天)";
        }
    }
    
    // 辅助方法：获取服务器连接地址
    public String getConnectionString() {
        if (sshPort == null || sshPort == 22) {
            return ip;
        } else {
            return ip + ":" + sshPort;
        }
    }
    
    // 辅助方法：获取带有多倍率的价格
    public BigDecimal getEffectivePrice() {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        
        if (multiple == null || multiple.compareTo(BigDecimal.ZERO) <= 0) {
            return price;
        }
        
        return price.multiply(multiple);
    }
}