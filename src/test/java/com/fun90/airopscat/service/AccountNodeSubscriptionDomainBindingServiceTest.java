package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountNodeSubscriptionDomainBindingRequest;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountNodeSubscriptionDomainBinding;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.convert.NodeConverter;
import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.repository.AccountNodeSubscriptionDomainBindingRepository;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.DomainDnsRecordRepository;
import com.fun90.airopscat.repository.NodeRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    private static AccountNodeSubscriptionDomainBindingService service(BindingRepository bindings) {
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
            public void validateUniformPlacementTags(Long nodeId) {
                // 此组测试只验证绑定记录本身的行为
            }

            @Override
            public List<NodeDto> toNodeDtos(List<Node> items) {
                return items.stream().map(node -> NodeConverter.toDto(node, List.of(), List.of())).toList();
            }
        };
        return new AccountNodeSubscriptionDomainBindingService(bindings, accounts, nodes, null, records, nodeService, tags);
    }

    private static AccountNodeSubscriptionDomainBindingRequest request(Long id, Long accountId, Long nodeId) {
        var request = new AccountNodeSubscriptionDomainBindingRequest();
        request.setId(id);
        request.setAccountId(accountId);
        request.setNodeId(nodeId);
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
    }
}
