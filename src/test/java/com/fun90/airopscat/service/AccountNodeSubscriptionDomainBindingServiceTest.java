package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountNodeSubscriptionDomainBindingRequest;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountNodeSubscriptionDomainBinding;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.convert.NodeConverter;
import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.AccountNodeSubscriptionDomainBindingRepository;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.DomainDnsRecordRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.NodeServerRepository;
import com.fun90.airopscat.repository.ServerRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountNodeSubscriptionDomainBindingServiceTest {

    @Test
    void editingUsesOriginalBindingEvenWhenNodeChanges() {
        var original = binding(10L, 1L, 2L);
        var repository = new BindingRepository(original);
        var service = service(repository);
        var request = request(10L, 1L, 3L);

        var result = service.save(request);

        assertEquals(10L, result.getId());
        assertEquals(3L, original.getNodeId());
        assertEquals(7L, original.getDomainDnsRecordId());
        assertEquals(0, repository.persistCount);
    }

    @Test
    void requestsWithoutIdStillUpdateMatchingAccountAndNode() {
        var original = binding(10L, 1L, 2L);
        var repository = new BindingRepository(original);
        var request = request(null, 1L, 2L);

        var result = service(repository).save(request);

        assertEquals(10L, result.getId());
        assertEquals(7L, original.getDomainDnsRecordId());
        assertEquals(0, repository.persistCount);
    }

    @Test
    void editingCannotModifyAnotherAccountsBinding() {
        var original = binding(10L, 1L, 2L);
        var service = service(new BindingRepository(original));

        assertThrows(IllegalArgumentException.class, () -> service.save(request(10L, 2L, 3L)));
        assertEquals(1L, original.getAccountId());
        assertEquals(2L, original.getNodeId());
    }

    @Test
    void editingCannotMoveOntoAnotherBinding() {
        var original = binding(10L, 1L, 2L);
        var service = service(new BindingRepository(original, binding(11L, 1L, 3L)));

        assertThrows(IllegalArgumentException.class, () -> service.save(request(10L, 1L, 3L)));
        assertEquals(2L, original.getNodeId());
    }

    @Test
    void editingMissingBindingDoesNotCreateOne() {
        var repository = new BindingRepository();
        var service = service(repository);

        assertThrows(EntityNotFoundException.class, () -> service.save(request(10L, 1L, 3L)));
        assertEquals(0, repository.persistCount);
    }

    @Test
    void savesSelectedPlacementWithoutDnsRecord() {
        var original = binding(10L, 1L, 2L);
        var request = request(10L, 1L, 2L);
        request.setDomainDnsRecordId(null);

        var domains = new DomainStub();
        service(new BindingRepository(original), domains).save(request);

        assertEquals(null, original.getDomainDnsRecordId());
        assertEquals(Set.of(21L), original.getNodeServerIds());
        assertEquals(List.of(6L), domains.released);
    }

    @Test
    void bindsSelectedRecordToPlacementServer() {
        var original = binding(10L, 1L, 2L);
        var domains = new DomainStub();

        service(new BindingRepository(original), domains).save(request(10L, 1L, 2L));

        assertEquals(7L, original.getDomainDnsRecordId());
        assertEquals(List.of(7L), domains.bound);
        assertEquals(List.of(6L), domains.released);
    }

    @Test
    void keepsRecordAlreadyBoundToSameServer() {
        var original = binding(10L, 1L, 2L);
        original.setDomainDnsRecordId(7L);
        var domains = new DomainStub();

        service(new BindingRepository(original), domains).save(request(10L, 1L, 2L));

        assertEquals(List.of(), domains.bound);
        assertEquals(List.of(), domains.released);
    }

    @Test
    void createsNewRecordWhenRequested() {
        var original = binding(10L, 1L, 2L);
        var request = request(10L, 1L, 2L);
        request.setDomainDnsRecordId(null);
        request.setNewDomainId(5L);
        request.setNewSubdomain("us1");

        var domains = new DomainStub();
        service(new BindingRepository(original), domains).save(request);

        assertEquals(88L, original.getDomainDnsRecordId());
        assertEquals(List.of("us1"), domains.created);
    }

    @Test
    void rejectsPlacementOfAnotherNode() {
        var original = binding(10L, 1L, 2L);
        var request = request(10L, 1L, 2L);
        request.setNodeServerId(31L);

        assertThrows(IllegalArgumentException.class, () -> service(new BindingRepository(original)).save(request));
        assertEquals(Set.of(), original.getNodeServerIds());
    }

    @Test
    void requiresPlacement() {
        var request = request(10L, 1L, 2L);
        request.setNodeServerId(null);

        assertThrows(IllegalArgumentException.class,
                () -> service(new BindingRepository(binding(10L, 1L, 2L))).save(request));
    }

    // 记录对 ServerDomainService 的调用；用匿名子类，避免被 Quarkus 当作第二个 bean
    private static class DomainStub {
        final List<Long> bound = new ArrayList<>();
        final List<Long> released = new ArrayList<>();
        final List<String> created = new ArrayList<>();

        ServerDomainService service() {
            return new ServerDomainService() {
                @Override
                public DomainDnsRecord bindExisting(Long recordId, Server server, Long excludePlacementId, Long excludeBindingId) {
                    bound.add(recordId);
                    DomainDnsRecord record = new DomainDnsRecord();
                    record.setId(recordId);
                    return record;
                }

                @Override
                public DomainDnsRecord createARecord(Long domainId, String subdomain, Server server) {
                    created.add(subdomain);
                    DomainDnsRecord record = new DomainDnsRecord();
                    record.setId(88L);
                    return record;
                }

                @Override
                public void releaseIfUnused(Long recordId) {
                    released.add(recordId);
                }
            };
        }
    }

    private static AccountNodeSubscriptionDomainBindingService service(BindingRepository bindings) {
        return service(bindings, new DomainStub());
    }

    private static AccountNodeSubscriptionDomainBindingService service(BindingRepository bindings, DomainStub domains) {
        var accounts = new AccountRepository() {
            @Override
            public Account findById(Long id) {
                Account account = new Account();
                account.setId(id);
                return account;
            }
        };
        var nodes = new NodeRepository() {
            @Override
            public Node findById(Long id) {
                Node node = new Node();
                node.setId(id);
                return node;
            }
        };
        var records = new DomainDnsRecordRepository() {
            @Override
            public DomainDnsRecord findById(Long id) {
                DomainDnsRecord record = new DomainDnsRecord();
                record.setId(id);
                record.setFullName("example.test");
                record.setServerId(2L);
                return record;
            }
        };
        var tags = new TagService(null, null, null) {
            @Override
            public List<Node> getAvailableNodesByAccount(Long accountId) {
                return List.of(nodes.findById(2L), nodes.findById(3L));
            }
        };
        var nodeService = new NodeService() {
            @Override
            public List<NodeDto> toNodeDtos(List<Node> items) {
                return items.stream().map(node -> NodeConverter.toDto(node, List.of(), List.of())).toList();
            }
        };
        var service = new AccountNodeSubscriptionDomainBindingService(bindings, accounts, nodes, null, records, nodeService, tags);
        service.nodeServerRepository = new NodeServerRepository() {
            @Override
            public List<NodeServer> findByNodeId(Long nodeId) {
                NodeServer placement = new NodeServer();
                placement.setId(nodeId * 10 + 1);
                placement.setNodeId(nodeId);
                placement.setServerId(nodeId);
                return List.of(placement);
            }
        };
        service.serverRepository = new ServerRepository() {
            @Override
            public Server findById(Long id) {
                Server server = new Server();
                server.setId(id);
                return server;
            }
        };
        service.serverDomainService = domains.service();
        return service;
    }

    private static AccountNodeSubscriptionDomainBindingRequest request(Long id, Long accountId, Long nodeId) {
        var request = new AccountNodeSubscriptionDomainBindingRequest();
        request.setId(id);
        request.setAccountId(accountId);
        request.setNodeId(nodeId);
        request.setNodeServerId(nodeId * 10 + 1);
        request.setDomainDnsRecordId(7L);
        return request;
    }

    private static AccountNodeSubscriptionDomainBinding binding(Long id, Long accountId, Long nodeId) {
        var binding = new AccountNodeSubscriptionDomainBinding();
        binding.setId(id);
        binding.setAccountId(accountId);
        binding.setNodeId(nodeId);
        binding.setDomainDnsRecordId(6L);
        return binding;
    }

    private static class BindingRepository extends AccountNodeSubscriptionDomainBindingRepository {
        private final List<AccountNodeSubscriptionDomainBinding> bindings;
        private int persistCount;

        private BindingRepository(AccountNodeSubscriptionDomainBinding... bindings) {
            this.bindings = List.of(bindings);
        }

        @Override
        public AccountNodeSubscriptionDomainBinding findById(Long id) {
            return bindings.stream().filter(binding -> Objects.equals(binding.getId(), id)).findFirst().orElse(null);
        }

        @Override
        public Optional<AccountNodeSubscriptionDomainBinding> findByAccountIdAndNodeId(Long accountId, Long nodeId) {
            return bindings.stream().filter(binding -> Objects.equals(binding.getAccountId(), accountId)
                    && Objects.equals(binding.getNodeId(), nodeId)).findFirst();
        }

        @Override
        public void persist(AccountNodeSubscriptionDomainBinding binding) {
            persistCount++;
        }

        @Override
        public void flush() {
        }
    }
}
