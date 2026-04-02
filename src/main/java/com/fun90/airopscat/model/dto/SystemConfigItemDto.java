package com.fun90.airopscat.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemConfigItemDto {
    private String key;
    private String label;
    private String description;
    private String inputType;
    private boolean required;
    private boolean sensitive;
    private boolean restartRequired;
    private boolean editable;
    private boolean storageEncrypted;
    private String placeholder;
    private String value;
}
