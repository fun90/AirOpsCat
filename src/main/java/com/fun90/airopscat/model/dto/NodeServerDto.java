package com.fun90.airopscat.model.dto;

import lombok.Data;

@Data
public class NodeServerDto {
    private Long id;
    private Long nodeId;
    private Long serverId;
    private String serverName;
    private String serverIp;
    private String serverHost;
    // 部署位域名（DNS 记录）
    private Long accessHostId;
    private String accessHost;
    // 域名记录的同步状态；非 SYNCED 时订阅仍使用服务器 IP
    private String accessHostStatus;
    private Integer exposed;
    private Integer priority;
    private Integer deployed;
}
