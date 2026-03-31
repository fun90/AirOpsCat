package com.fun90.airopscat.model.dto;

import com.fun90.airopscat.model.enums.DnsProviderConfigStatus;
import com.fun90.airopscat.model.enums.DnsProviderType;
import lombok.Data;

@Data
public class DnsProviderConfigRequest {
    private DnsProviderType providerType;
    private DnsProviderConfigStatus status;
    private String displayName;
    private String apiToken;
    private String accountId;
    private String remark;
}
