package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AccountDto {
    private Long id;
    private Integer level;
    private LocalDateTime fromDate;
    private LocalDateTime toDate;
    private String periodType;
    private String uuid;
    private String accountNo;
    private String authCode;
    private Integer maxOnlineIps;
    private Integer speed;
    private Integer bandwidth;
    private Integer disabled;
    private Long userId;
    private String userEmail; // 关联用户的邮箱
    private LocalDateTime createTime;
    private Long daysUntilExpiration; // 到期剩余天数
    
    // 流量使用情况
    private Long usedUploadBytes;    // 已使用上传流量
    private Long usedDownloadBytes;  // 已使用下载流量
    private Long totalUsedBytes;     // 总使用流量
    private Double usagePercentage;  // 使用百分比
    
    // 辅助方法：构建授权配置链接
    public String getConfigUrl(String baseUrl) {
        return baseUrl + "/config/" + uuid + (authCode != null ? "?auth=" + authCode : "");
    }
}