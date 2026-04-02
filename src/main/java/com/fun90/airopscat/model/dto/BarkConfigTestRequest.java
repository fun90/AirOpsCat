package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.Map;

@Data
public class BarkConfigTestRequest {
    private Map<String, String> values;
    private String title;
    private String body;
}
