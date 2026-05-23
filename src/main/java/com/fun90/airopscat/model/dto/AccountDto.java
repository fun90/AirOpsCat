package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AccountDto {
    private Long id;
    private Integer level;
    private Integer nodeMultiple;
    private String nodePrefix;
    private LocalDateTime fromDate;
    private LocalDateTime toDate;
    private String periodType;
    private String uuid;
    private String accountNo;
    private String authCode;
    private Integer maxConnections;
    private Integer speed;
    private Integer effectiveSpeed;
    private Boolean trafficOverQuotaLimited;
    private Integer bandwidth;
    private Integer downloadMbps;
    private Integer uploadMbps;
    private Integer disabled;
    private String remark; // 账户备注
    private Long userId;
    private String userEmail; // 关联用户的邮箱
    private String nickName; // 关联用户的昵称
    private LocalDateTime createTime;
    private Long daysUntilExpiration; // 到期剩余天数
    
    // 流量使用情况
    private Long effectiveBandwidth; // 当前周期实际生效配额（GB），周期配额优先，其次账户基准，null 表示不限量
    private Long usedUploadBytes;    // 已使用上传流量
    private Long usedDownloadBytes;  // 已使用下载流量
    private Long totalUsedBytes;     // 总使用流量
    private Double usagePercentage;  // 使用百分比
    
    // 在线连接信息
    private Integer onlineConnectionCount;
    private List<AccountOnlineIpDto> onlineConnections;
    private LocalDateTime lastOnlineTime; // 最后在线时间
}
