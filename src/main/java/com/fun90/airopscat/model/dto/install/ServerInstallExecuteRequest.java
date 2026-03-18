package com.fun90.airopscat.model.dto.install;

import lombok.Data;

@Data
public class ServerInstallExecuteRequest {
    private Long serverId;
    private String scriptName;
}
