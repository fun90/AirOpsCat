package com.fun90.airopscat.service;

import com.fun90.airopscat.model.convert.NodeConverter;
import com.fun90.airopscat.model.dto.AccountNodeSubscriptionDomainBindingDto;
import com.fun90.airopscat.model.dto.AccountNodeSubscriptionDomainBindingRequest;
import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountNodeSubscriptionDomainBinding;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.repository.AccountNodeSubscriptionDomainBindingRepository;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.DomainDnsRecordRepository;
import com.fun90.airopscat.repository.DomainRepository;
import com.fun90.airopscat.repository.NodeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.stream.Collectors;

@ApplicationScoped
public class AccountNodeSubscriptionDomainBindingService {

    private final AccountNodeSubscriptionDomainBindingRepository bindingRepository;
    private final AccountRepository accountRepository;
    private final NodeRepository nodeRepository;
    private final DomainRepository domainRepository;
    private final DomainDnsRecordRepository domainDnsRecordRepository;
    private final NodeService nodeService;
    private final TagService tagService;

    @Inject
    public AccountNodeSubscriptionDomainBindingService(AccountNodeSubscriptionDomainBindingRepository bindingRepository,
                                                       AccountRepository accountRepository,
                                                       NodeRepository nodeRepository,
                                                       DomainRepository domainRepository,
                                                       DomainDnsRecordRepository domainDnsRecordRepository,
                                                       NodeService nodeService,
                                                       TagService tagService) {
        this.bindingRepository = bindingRepository;
        this.accountRepository = accountRepository;
        this.nodeRepository = nodeRepository;
        this.domainRepository = domainRepository;
        this.domainDnsRecordRepository = domainDnsRecordRepository;
        this.nodeService = nodeService;
        this.tagService = tagService;
    }

    public List<AccountNodeSubscriptionDomainBindingDto> listByAccount(Long accountId) {
        ensureAccountExists(accountId);
        return toDtoList(bindingRepository.findByAccountId(accountId));
    }

    public List<AccountNodeSubscriptionDomainBindingDto> listByNode(Long nodeId) {
        ensureNodeExists(nodeId);
        return toDtoList(bindingRepository.findByNodeId(nodeId));
    }

    @Transactional
    public AccountNodeSubscriptionDomainBindingDto save(AccountNodeSubscriptionDomainBindingRequest request) {
        validateRequest(request);
        ensureAccountExists(request.getAccountId());
        ensureNodeExists(request.getNodeId());
        ensureNodeBelongsToAccount(request.getAccountId(), request.getNodeId());
        ensureDomainDnsRecordExists(request.getDomainDnsRecordId());

        AccountNodeSubscriptionDomainBinding binding = bindingRepository
                .findByAccountIdAndNodeId(request.getAccountId(), request.getNodeId())
                .orElseGet(AccountNodeSubscriptionDomainBinding::new);

        binding.setAccountId(request.getAccountId());
        binding.setNodeId(request.getNodeId());
        binding.setDomainDnsRecordId(request.getDomainDnsRecordId());
        binding.setEnabled(request.getEnabled() == null ? 1 : request.getEnabled());
        binding.setRemark(normalizeBlank(request.getRemark()));

        if (binding.getId() == null) {
            bindingRepository.persist(binding);
        }
        return toDto(binding);
    }

    @Transactional
    public AccountNodeSubscriptionDomainBindingDto setEnabled(Long id, boolean enabled) {
        AccountNodeSubscriptionDomainBinding binding = findExisting(id);
        binding.setEnabled(enabled ? 1 : 0);
        return toDto(binding);
    }

    @Transactional
    public void delete(Long id) {
        if (!bindingRepository.deleteById(id)) {
            throw new EntityNotFoundException("账户节点订阅域名绑定不存在");
        }
    }

    public Map<Long, String> findSubscriptionHostOverrides(Long accountId, List<Long> nodeIds) {
        if (accountId == null || nodeIds == null || nodeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<AccountNodeSubscriptionDomainBinding> bindings =
                bindingRepository.findEnabledByAccountIdAndNodeIds(accountId, nodeIds);
        if (bindings.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Long> recordIds = bindings.stream()
                .map(AccountNodeSubscriptionDomainBinding::getDomainDnsRecordId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, DomainDnsRecord> recordMap = domainDnsRecordRepository.findByIdIn(recordIds).stream()
                .collect(Collectors.toMap(DomainDnsRecord::getId, record -> record));

        Map<Long, String> overrides = new HashMap<>();
        for (AccountNodeSubscriptionDomainBinding binding : bindings) {
            DomainDnsRecord record = recordMap.get(binding.getDomainDnsRecordId());
            String host = record == null ? null : normalizeBlank(record.getFullName());
            if (host != null) {
                overrides.put(binding.getNodeId(), host);
            }
        }
        return overrides;
    }

    public Map<String, Object> searchAvailableNodes(Long accountId, String search, int size) {
        ensureAccountExists(accountId);
        int limit = size <= 0 ? 20 : Math.min(size, 100);
        String keyword = normalizeBlank(search);
        String lowerKeyword = keyword == null ? null : keyword.toLowerCase(Locale.ROOT);

        List<NodeDto> records = tagService.getAvailableNodesByAccount(accountId).stream()
                .sorted(Comparator.comparing(node -> normalizeSortText(node.getName())))
                .map(NodeConverter::toDto)
                .filter(dto -> lowerKeyword == null || matchesNode(dto, lowerKeyword))
                .limit(limit)
                .toList();
        nodeService.fillOnlineConnectionCounts(records);

        Map<String, Object> response = new HashMap<>();
        response.put("records", records);
        response.put("total", records.size());
        response.put("current", 1);
        response.put("size", limit);
        response.put("pages", 1);
        return response;
    }

    private void validateRequest(AccountNodeSubscriptionDomainBindingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        if (request.getAccountId() == null) {
            throw new IllegalArgumentException("账户不能为空");
        }
        if (request.getNodeId() == null) {
            throw new IllegalArgumentException("节点不能为空");
        }
        if (request.getDomainDnsRecordId() == null) {
            throw new IllegalArgumentException("DNS记录不能为空");
        }
    }

    private void ensureAccountExists(Long accountId) {
        if (accountId == null || accountRepository.findById(accountId) == null) {
            throw new EntityNotFoundException("账户不存在");
        }
    }

    private void ensureNodeExists(Long nodeId) {
        if (nodeId == null || nodeRepository.findById(nodeId) == null) {
            throw new EntityNotFoundException("节点不存在");
        }
    }

    private void ensureNodeBelongsToAccount(Long accountId, Long nodeId) {
        boolean belongs = tagService.getAvailableNodesByAccount(accountId).stream()
                .map(Node::getId)
                .anyMatch(id -> Objects.equals(id, nodeId));
        if (!belongs) {
            throw new IllegalArgumentException("节点不属于该账户");
        }
    }

    private void ensureDomainDnsRecordExists(Long recordId) {
        DomainDnsRecord record = recordId == null ? null : domainDnsRecordRepository.findById(recordId);
        if (record == null || normalizeBlank(record.getFullName()) == null) {
            throw new EntityNotFoundException("DNS记录不存在");
        }
    }

    private AccountNodeSubscriptionDomainBinding findExisting(Long id) {
        AccountNodeSubscriptionDomainBinding binding = id == null ? null : bindingRepository.findById(id);
        if (binding == null) {
            throw new EntityNotFoundException("账户节点订阅域名绑定不存在");
        }
        return binding;
    }

    private List<AccountNodeSubscriptionDomainBindingDto> toDtoList(List<AccountNodeSubscriptionDomainBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }

        List<Long> accountIds = bindings.stream().map(AccountNodeSubscriptionDomainBinding::getAccountId)
                .filter(Objects::nonNull).distinct().toList();
        List<Long> nodeIds = bindings.stream().map(AccountNodeSubscriptionDomainBinding::getNodeId)
                .filter(Objects::nonNull).distinct().toList();
        List<Long> recordIds = bindings.stream().map(AccountNodeSubscriptionDomainBinding::getDomainDnsRecordId)
                .filter(Objects::nonNull).distinct().toList();

        Map<Long, Account> accountMap = accountIds.isEmpty() ? Collections.emptyMap()
                : accountRepository.find("id in ?1", accountIds).list().stream()
                .collect(Collectors.toMap(Account::getId, account -> account));
        Map<Long, Node> nodeMap = nodeRepository.findByIdIn(nodeIds).stream()
                .collect(Collectors.toMap(Node::getId, node -> node));
        Map<Long, DomainDnsRecord> recordMap = domainDnsRecordRepository.findByIdIn(recordIds).stream()
                .collect(Collectors.toMap(DomainDnsRecord::getId, record -> record));
        List<Long> domainIds = recordMap.values().stream()
                .map(DomainDnsRecord::getDomainId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Domain> domainMap = domainIds.isEmpty() ? Collections.emptyMap()
                : domainRepository.find("id in ?1", domainIds).list().stream()
                .collect(Collectors.toMap(Domain::getId, domain -> domain));

        return bindings.stream()
                .map(binding -> {
                    DomainDnsRecord record = recordMap.get(binding.getDomainDnsRecordId());
                    Domain domain = record == null ? null : domainMap.get(record.getDomainId());
                    return toDto(binding, accountMap.get(binding.getAccountId()),
                            nodeMap.get(binding.getNodeId()), record, domain);
                })
                .toList();
    }

    private AccountNodeSubscriptionDomainBindingDto toDto(AccountNodeSubscriptionDomainBinding binding) {
        Account account = null;
        Node node = null;
        DomainDnsRecord record = null;
        Domain domain = null;
        if (binding.getAccountId() != null) {
            account = accountRepository.findById(binding.getAccountId());
        }
        if (binding.getNodeId() != null) {
            node = nodeRepository.findById(binding.getNodeId());
        }
        if (binding.getDomainDnsRecordId() != null) {
            record = domainDnsRecordRepository.findById(binding.getDomainDnsRecordId());
            if (record != null) {
                domain = record.getDomainId() == null ? null : domainRepository.findById(record.getDomainId());
            }
        }
        return toDto(binding, account, node, record, domain);
    }

    private AccountNodeSubscriptionDomainBindingDto toDto(AccountNodeSubscriptionDomainBinding binding,
                                                          Account account,
                                                          Node node,
                                                          DomainDnsRecord record,
                                                          Domain domain) {
        AccountNodeSubscriptionDomainBindingDto dto = new AccountNodeSubscriptionDomainBindingDto();
        dto.setId(binding.getId());
        dto.setAccountId(binding.getAccountId());
        dto.setNodeId(binding.getNodeId());
        dto.setDomainDnsRecordId(binding.getDomainDnsRecordId());
        dto.setEnabled(binding.getEnabled());
        dto.setRemark(binding.getRemark());
        dto.setCreateTime(binding.getCreateTime());
        dto.setUpdateTime(binding.getUpdateTime());

        if (account != null) {
            dto.setAccountNo(account.getAccountNo());
            dto.setAccountRemark(account.getRemark());
        }
        if (node != null) {
            NodeDto nodeDto = NodeConverter.toDto(node);
            dto.setNodeName(node.getName());
            dto.setNodeServerHost(nodeDto.getServerHost());
            dto.setNodePort(node.getPort());
        }
        if (record != null) {
            dto.setDomainId(record.getDomainId());
            dto.setFullName(record.getFullName());
            dto.setRecordName(record.getName());
            dto.setRecordType(record.getType());
        }
        if (domain != null) {
            dto.setDomain(domain.getDomain());
            dto.setDomainExpireDate(domain.getExpireDate());
        }
        return dto;
    }

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeSortText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean matchesNode(NodeDto dto, String keyword) {
        return containsIgnoreCase(dto.getName(), keyword)
                || containsIgnoreCase(dto.getServerHost(), keyword)
                || containsIgnoreCase(dto.getServerIp(), keyword)
                || containsIgnoreCase(dto.getProtocol(), keyword)
                || containsIgnoreCase(dto.getRemark(), keyword);
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }
}
