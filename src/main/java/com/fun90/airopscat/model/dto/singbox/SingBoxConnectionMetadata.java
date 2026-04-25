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

    /** 从 type 字段（格式 protocol/nodeTag）提取节点 tag，如 "vless/node_4" → "node_4"。 */
    public String resolveNodeTag() {
        if (type != null) {
            int slash = type.indexOf('/');
            if (slash >= 0 && slash < type.length() - 1) {
                return type.substring(slash + 1).trim();
            }
        }
        return null;
    }
}
