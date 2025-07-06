package com.fun90.airopscat.model.dto.xray.setting.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RealitySettings {
    private Boolean show;
    private String dest;
    private List<String> serverNames;
    private String privateKey;
    private String publicKey;
    private List<String> shortIds;
}