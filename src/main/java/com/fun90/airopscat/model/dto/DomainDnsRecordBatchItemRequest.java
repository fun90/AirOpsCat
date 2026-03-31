package com.fun90.airopscat.model.dto;

import lombok.Data;

@Data
public class DomainDnsRecordBatchItemRequest {
    private Long id;
    private String name;
    private String type;
    private String content;
    private Integer ttl;
    private Boolean proxied;
    private Integer priority;
    private String remark;
}
