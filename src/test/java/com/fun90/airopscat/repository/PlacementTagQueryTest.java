package com.fun90.airopscat.repository;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PlacementTagQueryTest {
    @Inject
    TagRepository tags;

    @Test
    void placementTagQueriesRunAgainstMappedJoinTable() {
        assertTrue(tags.findNodesByAccountIds(List.of(-1L)).isEmpty());
        assertTrue(tags.findByNodeId(-1L).isEmpty());
        assertTrue(tags.findTagIdsByPlacementIds(List.of(-1L)).isEmpty());
        assertTrue(tags.findNodesByTagId(-1L).isEmpty());
        assertTrue(tags.findNodeIdsByTagNameLike("__placement_tag_no_match__").isEmpty());
        assertEquals(0, tags.countNodesByTagId(-1L));
    }
}
