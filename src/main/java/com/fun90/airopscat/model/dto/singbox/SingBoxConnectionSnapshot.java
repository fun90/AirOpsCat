package com.fun90.airopscat.model.dto.singbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SingBoxConnectionSnapshot {
    private String id;
    private SingBoxConnectionMetadata metadata;
    private Long upload;
    private Long download;
    private String start;
}
