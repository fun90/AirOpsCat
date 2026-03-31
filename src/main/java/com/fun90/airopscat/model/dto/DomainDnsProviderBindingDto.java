package com.fun90.airopscat.model.dto;

import com.fun90.airopscat.model.enums.DnsProviderType;
import com.fun90.airopscat.model.enums.DnsSyncStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DomainDnsProviderBindingDto {
    private Long domainId;
    private Long dnsProviderConfigId;
    private String dnsProviderName;
    private DnsProviderType dnsProviderType;
    private String zoneId;
    private DnsSyncStatus dnsSyncStatus;
    private LocalDateTime dnsLastSyncTime;
}
