package com.fun90.airopscat.model.dto;

import com.fun90.airopscat.model.enums.NodeBatchTagUpdateMode;
import lombok.Data;

import java.util.List;

@Data
public class NodeBatchTagUpdateRequest {
    private List<Long> nodeIds;
    private List<Long> tagIds;
    private String mode;

    public NodeBatchTagUpdateMode resolveMode() {
        return NodeBatchTagUpdateMode.fromValue(mode);
    }
}
