package com.fun90.airopscat.model.dto;

import lombok.Data;

@Data
public class AccountNodeSubscriptionDomainBindingRequest {
    private Long id;
    private Long accountId;
    private Long nodeId;
    // 订阅中展示的部署位，必选
    private Long nodeServerId;
    // 选择已有的 DNS 记录；与 newDomainId 二选一，都不填则不覆盖连接地址
    private Long domainDnsRecordId;
    // 新建 A 记录：所属域名与子域名
    private Long newDomainId;
    private String newSubdomain;
    private Integer enabled;
    private String remark;
}
