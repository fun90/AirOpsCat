package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class NodeCoreSwitchRequest {
    private List<Long> nodeIds;
    private String targetCoreType;
    private Boolean redeploy;
}
