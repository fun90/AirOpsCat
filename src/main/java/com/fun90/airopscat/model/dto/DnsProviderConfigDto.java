package com.fun90.airopscat.model.dto;

import com.fun90.airopscat.model.enums.DnsProviderCheckStatus;
import com.fun90.airopscat.model.enums.DnsProviderConfigStatus;
import com.fun90.airopscat.model.enums.DnsProviderType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DnsProviderConfigDto {
    private Long id;
    private DnsProviderType providerType;
    private DnsProviderConfigStatus status;
    private String displayName;
    private boolean credentialConfigured;
    private String apiTokenMasked;
    private String accountId;
    private DnsProviderCheckStatus lastCheckStatus;
    private String lastCheckMessage;
    private LocalDateTime lastCheckTime;
    private Integer usedDomainCount;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
