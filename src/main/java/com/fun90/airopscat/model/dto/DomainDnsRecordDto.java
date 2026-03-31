package com.fun90.airopscat.model.dto;

import com.fun90.airopscat.model.enums.DnsRecordStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DomainDnsRecordDto {
    private Long id;
    private Long domainId;
    private Long dnsProviderConfigId;
    private String externalRecordId;
    private String name;
    private String fullName;
    private String type;
    private String content;
    private Integer ttl;
    private Boolean proxied;
    private Integer priority;
    private DnsRecordStatus status;
    private String remark;
    private String bizTagsJson;
    private String extensionJson;
    private String rawData;
    private LocalDateTime lastSyncTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
