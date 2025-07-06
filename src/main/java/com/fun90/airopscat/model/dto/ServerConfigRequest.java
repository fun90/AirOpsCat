package com.fun90.airopscat.model.dto;

import lombok.Data;

@Data
public class ServerConfigRequest {
    private Long id;
    private Long serverId;
    private String config;
    private String configType;
    private String path;
} 