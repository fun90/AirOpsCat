package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.Map;

@Data
public class SystemConfigUpdateRequest {
    private Map<String, String> values;
}
