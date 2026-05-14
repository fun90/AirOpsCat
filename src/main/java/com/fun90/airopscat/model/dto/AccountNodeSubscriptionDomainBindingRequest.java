package com.fun90.airopscat.model.dto;

import lombok.Data;

@Data
public class AccountNodeSubscriptionDomainBindingRequest {
    private Long accountId;
    private Long nodeId;
    private Long domainDnsRecordId;
    private Integer enabled;
    private String remark;
}
