package com.fun90.airopscat.model.dto;

import lombok.Data;

@Data
public class NodeServerRequest {
    private Long serverId;
    // 选择已有的 DNS 记录；与 newDomainId 二选一
    private Long accessHostId;
    // 新建 A 记录：所属域名与子域名
    private Long newDomainId;
    private String newSubdomain;
    private Integer exposed;
    private Integer priority;
}
