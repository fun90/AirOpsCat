package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.service.SubscriptionService.SubscriptionCandidate;
import com.fun90.airopscat.util.NodeObfuscator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionEntrySelectionTest {

    @Test
    void placementAuthorizationKeepsServersSeparate() {
        assertTrue(SubscriptionService.isAuthorizedPlacement(List.of(1L, 2L), java.util.Set.of(2L)));
        assertTrue(SubscriptionService.isAuthorizedPlacement(List.of(2L), java.util.Set.of(2L, 3L)));
        assertFalse(SubscriptionService.isAuthorizedPlacement(List.of(1L), java.util.Set.of(2L)));
        assertFalse(SubscriptionService.isAuthorizedPlacement(List.of(), java.util.Set.of(2L)));
    }

    @Test
    void accountBindingOutputsSingleEntryFromEffectivePlacement() {
        List<NodeDto> entries = SubscriptionService.selectSubscriptionEntries(List.of(
                candidate(2L, 20, 1, null, "hk2.example.com", "HK2"),
                candidate(1L, 10, 0, null, "hk1.example.com", "HK1")
        ), "bind.example.com", "dns.example.com");

        assertEquals(1, entries.size());
        assertEquals(1L, entries.getFirst().getNodeServerId());
        assertEquals("bind.example.com", entries.getFirst().getServerHost());
        assertEquals("香港-1", entries.getFirst().getName());
    }

    @Test
    void dnsRecordOutputsSingleEntryOnlyWhenAnyPlacementExposed() {
        List<NodeDto> entries = SubscriptionService.selectSubscriptionEntries(List.of(
                candidate(1L, 10, 0, null, "hk1.example.com", "HK1"),
                candidate(2L, 20, 1, null, "hk2.example.com", "HK2")
        ), null, "dns.example.com");

        assertEquals(1, entries.size());
        assertEquals(1L, entries.getFirst().getNodeServerId());
        assertEquals("dns.example.com", entries.getFirst().getServerHost());

        assertTrue(SubscriptionService.selectSubscriptionEntries(List.of(
                candidate(1L, 10, 0, null, "hk1.example.com", "HK1")
        ), null, "dns.example.com").isEmpty());
    }

    @Test
    void outputsEachExposedPlacementByPriority() {
        List<NodeDto> entries = SubscriptionService.selectSubscriptionEntries(List.of(
                candidate(3L, 30, 1, "香港备用", "hk3.example.com", "HK3"),
                candidate(1L, 10, 1, null, "hk1.example.com", "HK1"),
                candidate(2L, 20, 0, null, "hk2.example.com", "HK2")
        ), null, null);

        assertEquals(List.of(1L, 3L), entries.stream().map(NodeDto::getNodeServerId).toList());
        assertEquals(List.of("香港-HK1-1", "香港备用-1"), entries.stream().map(NodeDto::getName).toList());
        assertEquals("hk1.example.com", entries.getFirst().getServerHost());
    }

    @Test
    void singleExposedPlacementKeepsNodeName() {
        List<NodeDto> entries = SubscriptionService.selectSubscriptionEntries(List.of(
                candidate(1L, 10, 1, null, "hk1.example.com", "HK1")
        ), null, null);

        assertEquals(1, entries.size());
        assertEquals("香港-1", entries.getFirst().getName());
    }

    @Test
    void placementNameKeepsItsOwnNumber() {
        List<NodeDto> entries = SubscriptionService.selectSubscriptionEntries(List.of(
                candidate(1L, 99, 1, "东京-7", "jp.example.com", "JP")
        ), null, null);

        assertEquals("东京-7", entries.getFirst().getName());
    }

    @Test
    void duplicatedPlacementNamesStayUniqueAfterObfuscation() {
        NodeDto first = candidate(1L, 10, 1, "香港", "hk1.example.com", "HK1").dto();
        NodeDto second = candidate(2L, 20, 1, "香港", "hk2.example.com", "HK2").dto();
        first.setName("香港-3");
        second.setName("香港-3");
        List<NodeDto> entries = NodeObfuscator.obfuscate(List.of(first, second), 2, "");

        SubscriptionService.ensureUniqueNames(entries);

        assertEquals(List.of("香港-30", "香港-31", "香港-2-30", "香港-2-31"),
                entries.stream().map(NodeDto::getName).toList());
    }

    private static SubscriptionCandidate candidate(Long id, int priority, int exposed, String name, String host, String serverName) {
        NodeServer placement = new NodeServer();
        placement.setId(id);
        placement.setPriority(priority);
        placement.setExposed(exposed);
        placement.setEnabled(1);
        placement.setName(name);
        NodeDto dto = new NodeDto();
        dto.setNodeServerId(id);
        dto.setName("香港-1");
        dto.setServerHost(host);
        return new SubscriptionCandidate(placement, dto, serverName);
    }
}
