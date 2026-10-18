package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class NodeBatchDeleteRequest {
    private List<Long> nodeIds;
}
