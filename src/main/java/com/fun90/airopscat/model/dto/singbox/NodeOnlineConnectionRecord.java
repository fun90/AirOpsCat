package com.fun90.airopscat.model.dto.singbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeOnlineConnectionRecord {
    private String id;
    private String accountNo;
    private String clientIp;
    private String nodeTag;
    private String start;
}
