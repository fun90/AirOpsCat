package com.fun90.airopscat.model.dto.xray.setting.outbound;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
class BlackholeResponse {
    private String type;
}