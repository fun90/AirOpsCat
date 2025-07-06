package com.fun90.airopscat.model.convert;

import com.fun90.airopscat.model.dto.ServerConfigDto;
import com.fun90.airopscat.model.entity.ServerConfig;

public class ServerConfigConverter {
    
    public static ServerConfigDto toDto(ServerConfig serverConfig) {
        if (serverConfig == null) {
            return null;
        }
        
        ServerConfigDto dto = new ServerConfigDto();
        dto.setId(serverConfig.getId());
        dto.setServerId(serverConfig.getServerId());
        dto.setConfig(serverConfig.getConfig());
        dto.setConfigType(serverConfig.getConfigType());
        dto.setPath(serverConfig.getPath());
        dto.setCreateTime(serverConfig.getCreateTime());
        dto.setUpdateTime(serverConfig.getUpdateTime());
        
        // 获取关联的服务器信息
        if (serverConfig.getServer() != null) {
            dto.setServerIp(serverConfig.getServer().getIp());
            dto.setServerHost(serverConfig.getServer().getHost());
            dto.setServerName(serverConfig.getServer().getName());
        }
        
        return dto;
    }
} 