package com.fun90.airopscat.model.dto.xray.setting.outbound;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SocksServer {
    private String address;
    private Integer port;
    private List<SocksUser> users;
}