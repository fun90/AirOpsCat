package com.fun90.airopscat.model.dto;

import com.fun90.airopscat.model.enums.NodeType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
public class NodeDto {
    private Long id;
    private Long serverId;
    private String serverIp;
    private String serverHost;
    private Integer port;
    private String protocol;
    private Integer type;
    private String typeDescription;
    private Map<String, Object> inbound = new HashMap<>();
    private Long outId;
    private String outName;
    private String outServerHost;
    private Integer outPort;
    private Map<String, Object> rule = new HashMap<>();
    private Integer level;
    private Integer deployed;
    private Integer disabled;
    private String name;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // 辅助方法：获取状态描述
    public String getActiveDescription() {
        if (disabled != null && disabled == 1) {
            return "已禁用";
        }
        return "已启用";
    }
    
    // 辅助方法：获取状态描述
    public String getDeploymentStatusDescription() {
        if (deployed != null && deployed == 1) {
            return "已部署";
        }
        return "未部署";
    }
    
    // 辅助方法：获取类型描述
    public String getTypeDesc() {
        NodeType nodeType = NodeType.fromValue(type);
        return nodeType != null ? nodeType.getDescription() : "未知";
    }
    
    // 辅助方法：获取完整地址
    public String getFullAddress() {
        if (serverIp == null) {
            return null;
        }
        
        String host = serverHost != null && !serverHost.isEmpty() ? serverHost : serverIp;
        return host + ":" + port;
    }
}