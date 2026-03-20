package com.fun90.airopscat.model.dto;

import lombok.Data;

@Data
public class DefaultConfigDto<T> {
    private String coreType;
    private String protocol;
    private T config;
}
