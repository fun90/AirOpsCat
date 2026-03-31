package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class DomainDnsRecordBatchRequest {
    private String action;
    private List<DomainDnsRecordBatchItemRequest> items;
}
