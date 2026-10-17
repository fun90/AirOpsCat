package com.fun90.airopscat.model.convert;

import com.fun90.airopscat.model.dto.NodeRequest;
import com.fun90.airopscat.model.dto.NodeServerRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodePlacementTagRequestTest {
    @Test
    void missingTagsCanPreserveExistingPlacementTags() {
        NodeRequest request = new NodeRequest();
        NodeServerRequest placement = new NodeServerRequest();
        placement.setServerId(10L);
        request.setPlacements(List.of(placement));

        org.junit.jupiter.api.Assertions.assertNull(NodeConverter.placementsFromRequest(request).getFirst().getTags());
    }

    @Test
    void explicitPlacementTagsOverrideLegacyNodeTags() {
        NodeRequest request = new NodeRequest();
        request.setTagIds(List.of(1L));
        NodeServerRequest first = new NodeServerRequest();
        first.setServerId(10L);
        first.setTagIds(List.of(2L));
        NodeServerRequest second = new NodeServerRequest();
        second.setServerId(20L);
        request.setPlacements(List.of(first, second));

        var placements = NodeConverter.placementsFromRequest(request);

        assertEquals(List.of(2L), placements.getFirst().getTags().stream().map(tag -> tag.getId()).toList());
        assertEquals(List.of(1L), placements.get(1).getTags().stream().map(tag -> tag.getId()).toList());
    }
}
