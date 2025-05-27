package com.fun90.airopscat.model.dto.xray.setting.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SocksAccount {
    private String user;
    private String pass;
}