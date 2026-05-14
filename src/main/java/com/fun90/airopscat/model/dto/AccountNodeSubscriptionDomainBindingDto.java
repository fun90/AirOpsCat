package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
    private Long domainDnsRecordId;
    private Long domainId;
    private String fullName;
    private String recordName;
    private String recordType;
    private String domain;
    private LocalDate domainExpireDate;
    private Integer enabled;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
