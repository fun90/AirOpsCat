package com.fun90.airopscat.model.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

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
    private String remark; // 账户备注
    private Long userId;
    private String userEmail; // 关联用户的邮箱
    private String nickName; // 关联用户的昵称
    private LocalDateTime createTime;
    private Long daysUntilExpiration; // 到期剩余天数
    
    // 流量使用情况
    private Long usedUploadBytes;    // 已使用上传流量
    private Long usedDownloadBytes;  // 已使用下载流量
    private Long totalUsedBytes;     // 总使用流量
    private Double usagePercentage;  // 使用百分比
    
    // 在线IP信息
    private List<AccountOnlineIpDto> onlineIps; // 在线IP列表
}