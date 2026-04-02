package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class NodeBatchTagUpdateRequest {
    private List<Long> nodeIds;
    private List<Long> tagIds;
}
