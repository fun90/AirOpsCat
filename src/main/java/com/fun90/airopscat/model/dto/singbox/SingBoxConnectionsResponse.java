package com.fun90.airopscat.model.dto.singbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SingBoxConnectionsResponse {
    private List<SingBoxConnectionSnapshot> connections;
    private Long upload;
    private Long download;
}
