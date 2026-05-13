package com.fun90.airopscat.model.dto.singbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeOnlineConnectionsSnapshot {
    private Integer schemaVersion;
    private Long generatedAtEpochSeconds;
    private Integer ttlSeconds;
    private List<NodeOnlineConnectionRecord> connections;
}
