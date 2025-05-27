package com.fun90.airopscat.model.dto.xray.setting.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebSocketSettings {
    private String path;
    private Map<String, String> headers;
}