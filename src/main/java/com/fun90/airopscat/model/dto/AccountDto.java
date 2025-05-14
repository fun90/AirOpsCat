package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Data
public class AccountDto {
    private Long id;
    private Integer level;
    private LocalDateTime fromDate;
    private LocalDateTime toDate;
    private String periodType;
    private String uuid;
    private String authCode;
    private Integer maxOnlineIps;
    private Integer speed;
    private Integer bandwidth;
    private Integer disabled;
    private Long userId;
    private String userEmail; // 关联用户的邮箱
    private LocalDateTime createTime;
    private Long daysUntilExpiration; // 到期剩余天数
    private String statusType; // 状态类型：active, disabled, expired
    
    // 流量使用情况
    private Long usedUploadBytes;    // 已使用上传流量
    private Long usedDownloadBytes;  // 已使用下载流量
    private Long totalUsedBytes;     // 总使用流量
    private Double usagePercentage;  // 使用百分比
    
    // 辅助方法：获取到期状态描述
    public String getStatusDescription() {
        if (disabled != null && disabled == 1) {
            return "已禁用";
        }
        
        if (toDate == null) {
            return "永不过期";
        }
        
        long days = ChronoUnit.DAYS.between(LocalDateTime.now(), toDate);
        if (days < 0) {
            return "已过期 " + Math.abs(days) + " 天";
        } else if (days == 0) {
            long hours = ChronoUnit.HOURS.between(LocalDateTime.now(), toDate);
            if (hours <= 0) {
                return "即将过期 " + ChronoUnit.MINUTES.between(LocalDateTime.now(), toDate) + " 分钟";
            }
            return "今天过期 " + hours + " 小时后";
        } else if (days <= 7) {
            return days + " 天后过期";
        } else {
            return "正常 (还有 " + days + " 天)";
        }
    }
    
    // 辅助方法：构建授权配置链接
    public String getConfigUrl(String baseUrl) {
        return baseUrl + "/config/" + uuid + (authCode != null ? "?auth=" + authCode : "");
    }
}