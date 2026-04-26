package com.fun90.airopscat.model.dto.maintenance;

import lombok.Data;

@Data
public class ServerMaintenanceExecuteRequest {
    private Long serverId;
    private String scriptName;
}
