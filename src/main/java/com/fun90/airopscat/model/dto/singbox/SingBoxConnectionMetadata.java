package com.fun90.airopscat.model.dto.singbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SingBoxConnectionMetadata {
    private String network;
    private String type;
    private String sourceIP;
    private Integer sourcePort;
    private String destinationIP;
    private Integer destinationPort;
    private String domain;
    private String authUser;
}
