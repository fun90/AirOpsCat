package com.fun90.airopscat.model.dto.xray;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

// API配置
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiConfig {
    private List<String> services;
    private String tag;
}