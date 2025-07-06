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