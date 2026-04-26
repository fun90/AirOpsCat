package com.fun90.airopscat.model.dto.maintenance;

import lombok.Data;

@Data
public class MaintenanceScriptDto {
    private Integer order;
    private String fileName;
    private String title;
    private String description;
}
