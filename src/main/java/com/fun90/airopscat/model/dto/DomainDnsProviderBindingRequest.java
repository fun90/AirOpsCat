package com.fun90.airopscat.model.dto;

import lombok.Data;

@Data
public class DomainDnsProviderBindingRequest {
    private Long dnsProviderConfigId;
    private String zoneId;
}
