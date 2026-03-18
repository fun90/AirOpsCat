package com.fun90.airopscat.model.dto.install;

import lombok.Data;

@Data
public class InstallScriptDto {
    private Integer order;
    private String fileName;
    private String title;
    private String description;
}
