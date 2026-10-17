package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class AccountNodeSubscriptionDomainBindingRequest {
    private Long id;
    private Long accountId;
    private Long nodeId;
    private Long domainDnsRecordId;
    private List<Long> nodeServerIds;
    private Integer enabled;
    private String remark;
}
