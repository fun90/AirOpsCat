package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ServerConfigDto {
    private Long id;
    private Long serverId;
    private String config;
    private String configType;
    private String path;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    
    // 关联的服务器信息
    private String serverIp;
    private String serverHost;
    private String serverName;
} 