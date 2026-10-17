package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.service.SubscriptionService.SubscriptionCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionEntrySelectionTest {

    @Test
    void accountBindingOutputsSingleEntryFromEffectivePlacement() {
        List<NodeDto> entries = SubscriptionService.selectSubscriptionEntries(List.of(
                candidate(2L, 20, 1, "hk2.example.com"),
                candidate(1L, 10, 0, "hk1.example.com")
        ), null, "bind.example.com", "dns.example.com");

        assertEquals(1, entries.size());
        assertEquals(1L, entries.getFirst().getNodeServerId());
        assertEquals("bind.example.com", entries.getFirst().getServerHost());
        assertEquals("香港-1", entries.getFirst().getName());
    }

    @Test
    void dnsRecordOutputsSingleEntryOnlyWhenAnyPlacementExposed() {
        List<NodeDto> entries = SubscriptionService.selectSubscriptionEntries(List.of(
                candidate(1L, 10, 0, "hk1.example.com"),
                candidate(2L, 20, 1, "hk2.example.com")
        ), null, null, "dns.example.com");

        assertEquals(1, entries.size());
        assertEquals(1L, entries.getFirst().getNodeServerId());
        assertEquals("dns.example.com", entries.getFirst().getServerHost());

        assertTrue(SubscriptionService.selectSubscriptionEntries(List.of(
                candidate(1L, 10, 0, "hk1.example.com")
        ), null, null, "dns.example.com").isEmpty());
    }

    @Test
    void outputsEachExposedPlacementByPriorityWithNodeName() {
        List<NodeDto> entries = SubscriptionService.selectSubscriptionEntries(List.of(
                candidate(3L, 30, 1, "hk3.example.com"),
                candidate(1L, 10, 1, "hk1.example.com"),
                candidate(2L, 20, 0, "hk2.example.com")
        ), null, null, null);

        assertEquals(List.of(1L, 3L), entries.stream().map(NodeDto::getNodeServerId).toList());
        assertEquals(List.of("香港-1", "香港-1"), entries.stream().map(NodeDto::getName).toList());
        assertEquals("hk1.example.com", entries.getFirst().getServerHost());
    }

    @Test
    void singleExposedPlacementKeepsNodeName() {
        List<NodeDto> entries = SubscriptionService.selectSubscriptionEntries(List.of(
                candidate(1L, 10, 1, "hk1.example.com")
        ), null, null, null);

        assertEquals(1, entries.size());
        assertEquals("香港-1", entries.getFirst().getName());
    }

    @Test
    void selectedPlacementsOutputEachRegardlessOfExposedAndDns() {
        List<NodeDto> entries = SubscriptionService.selectSubscriptionEntries(List.of(
                candidate(3L, 30, 0, "hk3.example.com"),
                candidate(1L, 10, 1, "hk1.example.com"),
                candidate(2L, 20, 0, "hk2.example.com")
        ), Set.of(3L, 2L), null, "dns.example.com");

        assertEquals(List.of(2L, 3L), entries.stream().map(NodeDto::getNodeServerId).toList());
        assertEquals("hk2.example.com", entries.getFirst().getServerHost());
    }

    @Test
    void accountBindingUsesFirstSelectedPlacement() {
        List<NodeDto> entries = SubscriptionService.selectSubscriptionEntries(List.of(
                candidate(1L, 10, 1, "hk1.example.com"),
                candidate(2L, 20, 1, "hk2.example.com")
        ), Set.of(2L), "bind.example.com", null);

        assertEquals(1, entries.size());
        assertEquals(2L, entries.getFirst().getNodeServerId());
        assertEquals("bind.example.com", entries.getFirst().getServerHost());
    }

    @Test
    void unavailableSelectionOutputsNothing() {
        assertTrue(SubscriptionService.selectSubscriptionEntries(List.of(
                candidate(1L, 10, 1, "hk1.example.com")
        ), Set.of(9L), "bind.example.com", "dns.example.com").isEmpty());
    }

    @Test
    void duplicateNamesGetIncreasingNumbersInOrder() {
        List<NodeDto> nodes = new java.util.ArrayList<>();
        for (String name : List.of("香港-1", "香港-1", "香港-2", "香港-1")) {
            NodeDto dto = new NodeDto();
            dto.setName(name);
            nodes.add(dto);
        }

        SubscriptionService.ensureUniqueNames(nodes);

        assertEquals(List.of("香港-1", "香港-3", "香港-2", "香港-4"),
                nodes.stream().map(NodeDto::getName).toList());
    }

    private static SubscriptionCandidate candidate(Long id, int priority, int exposed, String host) {
        NodeServer placement = new NodeServer();
        placement.setId(id);
        placement.setPriority(priority);
        placement.setExposed(exposed);
        placement.setEnabled(1);
        NodeDto dto = new NodeDto();
        dto.setNodeServerId(id);
        dto.setName("香港-1");
        dto.setServerHost(host);
        return new SubscriptionCandidate(placement, dto);
    }
}
