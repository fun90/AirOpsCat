package com.fun90.airopscat.model.dto.xray.setting.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Certificate {
    private Integer ocspStapling;
    private String certificateFile;
    private String keyFile;
}