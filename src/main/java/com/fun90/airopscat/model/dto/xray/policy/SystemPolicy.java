package com.fun90.airopscat.model.dto.xray.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SystemPolicy {
    private Boolean statsInboundDownlink;
    private Boolean statsInboundUplink;
}