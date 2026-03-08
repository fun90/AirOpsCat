package com.fun90.airopscat.model.dto.xray.setting;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fun90.airopscat.model.dto.xray.setting.stream.*;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Sniffing {
    private Boolean enabled;
    private List<String> destOverride;
    private Boolean routeOnly;
}