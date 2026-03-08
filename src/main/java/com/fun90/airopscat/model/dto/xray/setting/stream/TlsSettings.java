package com.fun90.airopscat.model.dto.xray.setting.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TlsSettings {
    private Boolean rejectUnknownSni;
    private String minVersion;
    private List<Certificate> certificates;
}