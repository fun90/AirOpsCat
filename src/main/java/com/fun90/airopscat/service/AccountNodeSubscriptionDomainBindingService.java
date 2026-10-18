package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountNodeSubscriptionDomainBindingDto;
import com.fun90.airopscat.model.dto.AccountNodeSubscriptionDomainBindingRequest;
import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountNodeSubscriptionDomainBinding;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.DnsRecordStatus;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.repository.AccountNodeSubscriptionDomainBindingRepository;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.DomainDnsRecordRepository;
import com.fun90.airopscat.repository.DomainRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.NodeServerRepository;
import com.fun90.airopscat.repository.ServerRepository;
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
import java.util.Set;
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
    NodeServerRepository nodeServerRepository;

    @Inject
    ServerRepository serverRepository;

    @Inject
    ServerDomainService serverDomainService;

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
        NodeServer placement = requirePlacement(request);

        AccountNodeSubscriptionDomainBinding binding;
        if (request.getId() != null) {
            binding = findExisting(request.getId());
            if (!Objects.equals(binding.getAccountId(), request.getAccountId())) {
                throw new IllegalArgumentException("绑定不属于该账户");
            }
            if (bindingRepository.findByAccountIdAndNodeId(request.getAccountId(), request.getNodeId())
                    .filter(existing -> !Objects.equals(existing.getId(), binding.getId())).isPresent()) {
                throw new IllegalArgumentException("该节点已有订阅定制");
            }
        } else {
            binding = bindingRepository.findByAccountIdAndNodeId(request.getAccountId(), request.getNodeId())
                    .orElseGet(AccountNodeSubscriptionDomainBinding::new);
        }

        Long previousRecordId = binding.getDomainDnsRecordId();
        Long recordId = resolveRecordId(request, binding, serverRepository.findById(placement.getServerId()));

        binding.setAccountId(request.getAccountId());
        binding.setNodeId(request.getNodeId());
        binding.setDomainDnsRecordId(recordId);
        binding.getNodeServerIds().clear();
        binding.getNodeServerIds().add(placement.getId());
        binding.setEnabled(request.getEnabled() == null ? 1 : request.getEnabled());
        binding.setRemark(normalizeBlank(request.getRemark()));

        if (binding.getId() == null) {
            bindingRepository.persist(binding);
        }
        if (!Objects.equals(previousRecordId, recordId)) {
            bindingRepository.flush();
            serverDomainService.releaseIfUnused(previousRecordId);
        }
        return toDto(binding);
    }

    // 域名绑定到所选部署位的服务器；存量已绑定同一服务器的记录保持不变，其余按“选已有 / 新建”校验
    private Long resolveRecordId(AccountNodeSubscriptionDomainBindingRequest request,
                                 AccountNodeSubscriptionDomainBinding binding, Server server) {
        if (request.getNewDomainId() != null) {
            return serverDomainService.createARecord(request.getNewDomainId(), request.getNewSubdomain(), server).getId();
        }
        Long recordId = request.getDomainDnsRecordId();
        if (recordId == null) {
            return null;
        }
        if (Objects.equals(recordId, binding.getDomainDnsRecordId())) {
            DomainDnsRecord current = domainDnsRecordRepository.findById(recordId);
            if (current != null && Objects.equals(current.getServerId(), server.getId())
                    && Objects.equals(binding.getNodeId(), request.getNodeId())
                    && binding.getNodeServerIds().equals(Set.of(request.getNodeServerId()))) {
                return recordId;
            }
        }
        return serverDomainService.bindExisting(recordId, server, request.getNodeServerId(), binding.getId()).getId();
    }

    @Transactional
    public AccountNodeSubscriptionDomainBindingDto setEnabled(Long id, boolean enabled) {
        AccountNodeSubscriptionDomainBinding binding = findExisting(id);
        binding.setEnabled(enabled ? 1 : 0);
        return toDto(binding);
    }

    @Transactional
    public void delete(Long id) {
        AccountNodeSubscriptionDomainBinding binding = findExisting(id);
        Long recordId = binding.getDomainDnsRecordId();
        bindingRepository.delete(binding);
        bindingRepository.flush();
        serverDomainService.releaseIfUnused(recordId);
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
            // 新建的记录推送成功前解析不到，订阅暂用部署位的默认地址
            String host = record == null || record.getStatus() != DnsRecordStatus.SYNCED
                    ? null : normalizeBlank(record.getFullName());
            if (host != null) {
                overrides.put(binding.getNodeId(), host);
            }
        }
        return overrides;
    }

    /**
     * @return 节点 ID → 账户订阅中展示的部署位 ID；未定制的节点不在结果中
     */
    public Map<Long, Set<Long>> findSubscriptionPlacementSelections(Long accountId, List<Long> nodeIds) {
        if (accountId == null || nodeIds == null || nodeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return bindingRepository.findEnabledByAccountIdAndNodeIds(accountId, nodeIds).stream()
                .filter(binding -> !binding.getNodeServerIds().isEmpty())
                .collect(Collectors.toMap(AccountNodeSubscriptionDomainBinding::getNodeId,
                        binding -> Set.copyOf(binding.getNodeServerIds())));
    }

    public Map<String, Object> searchAvailableNodes(Long accountId, String search, int size) {
        ensureAccountExists(accountId);
        int limit = size <= 0 ? 20 : Math.min(size, 100);
        String keyword = normalizeBlank(search);
        String lowerKeyword = keyword == null ? null : keyword.toLowerCase(Locale.ROOT);

        List<Node> availableNodes = tagService.getAvailableNodesByAccount(accountId).stream()
                .sorted(Comparator.comparing(node -> normalizeSortText(node.getName())))
                .toList();
        List<NodeDto> records = nodeService.toNodeDtos(availableNodes).stream()
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
        if (request.getNodeServerId() == null) {
            throw new IllegalArgumentException("请选择展示的部署位");
        }
    }

    private NodeServer requirePlacement(AccountNodeSubscriptionDomainBindingRequest request) {
        return nodeServerRepository.findByNodeId(request.getNodeId()).stream()
                .filter(placement -> !NodeDeploymentStatus.isPendingDelete(placement.getDeployed()))
                .filter(placement -> Objects.equals(placement.getId(), request.getNodeServerId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("部署位不属于该节点"));
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
        Map<Long, NodeDto> nodeMap = nodeService.toNodeDtos(nodeRepository.findByIdIn(nodeIds)).stream()
                .collect(Collectors.toMap(NodeDto::getId, node -> node));
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
        NodeDto node = null;
        DomainDnsRecord record = null;
        Domain domain = null;
        if (binding.getAccountId() != null) {
            account = accountRepository.findById(binding.getAccountId());
        }
        if (binding.getNodeId() != null) {
            Node entity = nodeRepository.findById(binding.getNodeId());
            node = entity == null ? null : nodeService.toNodeDtos(List.of(entity)).getFirst();
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
                                                          NodeDto node,
                                                          DomainDnsRecord record,
                                                          Domain domain) {
        AccountNodeSubscriptionDomainBindingDto dto = new AccountNodeSubscriptionDomainBindingDto();
        dto.setId(binding.getId());
        dto.setAccountId(binding.getAccountId());
        dto.setNodeId(binding.getNodeId());
        dto.setDomainDnsRecordId(binding.getDomainDnsRecordId());
        dto.setNodeServerId(binding.getNodeServerIds().stream().findFirst().orElse(null));
        dto.setEnabled(binding.getEnabled());
        dto.setRemark(binding.getRemark());
        dto.setCreateTime(binding.getCreateTime());
        dto.setUpdateTime(binding.getUpdateTime());

        if (account != null) {
            dto.setAccountNo(account.getAccountNo());
            dto.setAccountRemark(account.getRemark());
        }
        if (node != null) {
            dto.setNodeName(node.getName());
            dto.setNodeServerHost(node.getServerHost());
            dto.setNodePort(node.getPort());
            dto.setNodePlacements(node.getPlacements());
        }
        if (record != null) {
            dto.setDomainId(record.getDomainId());
            dto.setFullName(record.getFullName());
            dto.setRecordName(record.getName());
            dto.setRecordType(record.getType());
            dto.setRecordStatus(record.getStatus() == null ? null : record.getStatus().name());
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
