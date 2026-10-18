package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class AccountNodeSubscriptionDomainBindingDto {
    private Long id;
    private Long accountId;
    private String accountNo;
    private String accountRemark;
    private Long nodeId;
    private String nodeName;
    private String nodeServerHost;
    private Integer nodePort;
    private List<NodeServerDto> nodePlacements;
    private Long nodeServerId;
    private Long domainDnsRecordId;
    private Long domainId;
    private String fullName;
    private String recordName;
    private String recordType;
    // 记录的同步状态；非 SYNCED 时订阅暂不使用该域名
    private String recordStatus;
    private String domain;
    private LocalDate domainExpireDate;
    private Integer enabled;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
