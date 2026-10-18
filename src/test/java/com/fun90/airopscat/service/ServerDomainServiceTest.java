package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.AccountNodeSubscriptionDomainBinding;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.DnsRecordStatus;
import com.fun90.airopscat.repository.AccountNodeSubscriptionDomainBindingRepository;
import com.fun90.airopscat.repository.DomainDnsRecordRepository;
import com.fun90.airopscat.repository.NodeServerRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerDomainServiceTest {
    private final Server server = new Server();
    private final DomainDnsRecord record = new DomainDnsRecord();
    private final NodeServer placement = placement(51L, 83L, 2L);
    private final List<NodeServer> placements = new ArrayList<>(List.of(placement));
    private final List<AccountNodeSubscriptionDomainBinding> bindings = new ArrayList<>();
    private final ServerDomainService service = new ServerDomainService();

    ServerDomainServiceTest() {
        server.setId(2L);
        server.setIp("192.0.2.2");
        record.setId(897L);
        record.setFullName("shared.example.test");
        record.setType("A");
        record.setStatus(DnsRecordStatus.SYNCED);
        record.setServerId(2L);
        record.setContent(server.getIp());
        service.domainDnsRecordRepository = new DomainDnsRecordRepository() {
            @Override
            public DomainDnsRecord findById(Long id) {
                return Objects.equals(id, record.getId()) ? record : null;
            }

            @Override
            public List<DomainDnsRecord> list(String query, Object... params) {
                return List.of(record);
            }
        };
        service.nodeServerRepository = new NodeServerRepository() {
            @Override
            public NodeServer findById(Long id) {
                return placements.stream().filter(item -> Objects.equals(item.getId(), id)).findFirst().orElse(null);
            }

            @Override
            public boolean existsByAccessHostIdAndIdNot(Long recordId, Long excludeId) {
                return placements.stream().anyMatch(item -> Objects.equals(item.getAccessHostId(), recordId)
                        && !Objects.equals(item.getId(), excludeId));
            }
        };
        service.bindingRepository = new AccountNodeSubscriptionDomainBindingRepository() {
            @Override
            public long count(String query, Object... params) {
                return list(query, params).size();
            }

            @Override
            public List<AccountNodeSubscriptionDomainBinding> list(String query, Object... params) {
                return bindings.stream().filter(item -> Objects.equals(item.getDomainDnsRecordId(), params[0]))
                        .filter(item -> params.length < 2 || !Objects.equals(item.getId(), params[1])).toList();
            }
        };
    }

    @Test
    void placementCanUseDomainAlreadyUsedByItsAccountCustomization() {
        bindings.add(binding(15L, placement));

        assertEquals(record, service.bindExisting(record.getId(), server, placement.getId(), null));
    }

    @Test
    void sharedDomainAppearsInPlacementAndAccountChoices() {
        bindings.add(binding(15L, placement));
        placement.setAccessHostId(record.getId());

        assertEquals(List.of(record.getId()), service.getAvailableRecords(server, placement.getId()).stream()
                .map(item -> item.get("id")).toList());
        assertEquals(record, service.bindExisting(record.getId(), server, placement.getId(), 16L));
    }

    @Test
    void multipleAccountsCanShareOnePlacementDomain() {
        bindings.add(binding(15L, placement));
        bindings.add(binding(16L, placement));

        assertEquals(record, service.bindExisting(record.getId(), server, placement.getId(), 17L));
    }

    @Test
    void anotherPlacementCannotReuseDomainEvenOnSameServer() {
        var other = placement(52L, 84L, server.getId());
        other.setAccessHostId(record.getId());
        placements.add(other);

        assertThrows(IllegalArgumentException.class,
                () -> service.bindExisting(record.getId(), server, placement.getId(), null));
        assertEquals(List.of(), service.getAvailableRecords(server, placement.getId()));
    }

    @Test
    void anotherNodesAccountCustomizationBlocksReuse() {
        var conflicting = binding(15L, placement);
        conflicting.setNodeId(84L);
        bindings.add(conflicting);

        assertThrows(IllegalArgumentException.class,
                () -> service.bindExisting(record.getId(), server, placement.getId(), null));
        assertEquals(List.of(), service.getAvailableRecords(server, placement.getId()));
    }

    @Test
    void sameNodesOtherPlacementBlocksReuse() {
        bindings.add(binding(15L, placement(52L, placement.getNodeId(), 3L)));

        assertThrows(IllegalArgumentException.class,
                () -> service.bindExisting(record.getId(), server, placement.getId(), null));
        assertEquals(List.of(), service.getAvailableRecords(server, placement.getId()));
    }

    @Test
    void withoutPlacementContextOnlyUnusedDomainsAreAvailable() {
        assertEquals(1, service.getAvailableRecords(server, null).size());
        bindings.add(binding(15L, placement));

        assertEquals(List.of(), service.getAvailableRecords(server, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.bindExisting(record.getId(), server, null, null));
    }

    @Test
    void placementMustBelongToRequestedServer() {
        placement.setServerId(3L);

        assertThrows(IllegalArgumentException.class,
                () -> service.getAvailableRecords(server, placement.getId()));
        assertThrows(IllegalArgumentException.class,
                () -> service.bindExisting(record.getId(), server, placement.getId(), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.getAvailableRecords(server, 999L));
    }

    @Test
    void sharingDoesNotAllowScheduledOrDeletedOrNonARecords() {
        bindings.add(binding(15L, placement));
        record.setScheduleEnabled(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.bindExisting(record.getId(), server, placement.getId(), null));
        record.setScheduleEnabled(false);
        record.setStatus(DnsRecordStatus.PENDING_DELETE);
        assertThrows(IllegalArgumentException.class,
                () -> service.bindExisting(record.getId(), server, placement.getId(), null));
        record.setStatus(DnsRecordStatus.SYNCED);
        record.setType("CNAME");
        assertThrows(IllegalArgumentException.class,
                () -> service.bindExisting(record.getId(), server, placement.getId(), null));
    }

    @Test
    void removingOneReferenceKeepsServerBindingUntilLastReferenceIsRemoved() {
        bindings.add(binding(15L, placement));
        service.releaseIfUnused(record.getId());
        assertEquals(server.getId(), record.getServerId());
        bindings.clear();
        placement.setAccessHostId(record.getId());
        service.releaseIfUnused(record.getId());
        assertEquals(server.getId(), record.getServerId());
        placement.setAccessHostId(null);
        service.releaseIfUnused(record.getId());
        assertEquals(null, record.getServerId());
    }

    private AccountNodeSubscriptionDomainBinding binding(Long id, NodeServer target) {
        var binding = new AccountNodeSubscriptionDomainBinding();
        binding.setId(id);
        binding.setNodeId(target.getNodeId());
        binding.setDomainDnsRecordId(record.getId());
        binding.getNodeServerIds().add(target.getId());
        return binding;
    }

    private static NodeServer placement(Long id, Long nodeId, Long serverId) {
        var placement = new NodeServer();
        placement.setId(id);
        placement.setNodeId(nodeId);
        placement.setServerId(serverId);
        return placement;
    }
}
