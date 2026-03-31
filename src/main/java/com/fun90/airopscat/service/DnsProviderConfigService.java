package com.fun90.airopscat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.DnsProviderConfigDto;
import com.fun90.airopscat.model.dto.DnsProviderConfigRequest;
import com.fun90.airopscat.model.dto.DnsProviderTestResponse;
import com.fun90.airopscat.model.dto.DomainDto;
import com.fun90.airopscat.model.entity.DnsProviderConfig;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.model.enums.DnsProviderCheckStatus;
import com.fun90.airopscat.model.enums.DnsProviderConfigStatus;
import com.fun90.airopscat.model.enums.DnsProviderType;
import com.fun90.airopscat.repository.DnsProviderConfigRepository;
import com.fun90.airopscat.repository.DomainRepository;
import com.fun90.airopscat.service.dns.DnsProviderClient;
import com.fun90.airopscat.service.dns.DnsProviderClientRegistry;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class DnsProviderConfigService {

    private final DnsProviderConfigRepository dnsProviderConfigRepository;
    private final DomainRepository domainRepository;
    private final DomainService domainService;
    private final DnsProviderClientRegistry providerClientRegistry;
    private final ObjectMapper objectMapper;

    @Inject
    public DnsProviderConfigService(DnsProviderConfigRepository dnsProviderConfigRepository,
                                    DomainRepository domainRepository,
                                    DomainService domainService,
                                    DnsProviderClientRegistry providerClientRegistry,
                                    ObjectMapper objectMapper) {
        this.dnsProviderConfigRepository = dnsProviderConfigRepository;
        this.domainRepository = domainRepository;
        this.domainService = domainService;
        this.providerClientRegistry = providerClientRegistry;
        this.objectMapper = objectMapper;
    }

    public PanacheQuery<DnsProviderConfig> getPage(String search,
                                                   DnsProviderType providerType,
                                                   DnsProviderConfigStatus status) {
        Sort sort = Sort.by("createTime").descending();

        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>();

        if (search != null && !search.trim().isEmpty()) {
            conditions.add("(lower(displayName) like :search or lower(remark) like :search)");
            params.put("search", "%" + search.trim().toLowerCase() + "%");
        }

        if (providerType != null) {
            conditions.add("providerType = :providerType");
            params.put("providerType", providerType);
        }

        if (status != null) {
            conditions.add("status = :status");
            params.put("status", status);
        }

        if (conditions.isEmpty()) {
            return dnsProviderConfigRepository.findAll(sort);
        }
        return dnsProviderConfigRepository.find(String.join(" and ", conditions), sort, params);
    }

    public DnsProviderConfig getById(Long id) {
        return dnsProviderConfigRepository.findById(id);
    }

    public DnsProviderConfigDto convertToDto(DnsProviderConfig config) {
        DnsProviderConfigDto dto = new DnsProviderConfigDto();
        dto.setId(config.getId());
        dto.setProviderType(config.getProviderType());
        dto.setStatus(config.getStatus());
        dto.setDisplayName(config.getDisplayName());
        dto.setCredentialConfigured(config.getCredentialJson() != null && !config.getCredentialJson().isBlank());
        dto.setApiTokenMasked(maskApiToken(extractJsonValue(config.getCredentialJson(), "apiToken")));
        dto.setAccountId(extractJsonValue(config.getExtensionJson(), "accountId"));
        dto.setLastCheckStatus(config.getLastCheckStatus());
        dto.setLastCheckMessage(config.getLastCheckMessage());
        dto.setLastCheckTime(config.getLastCheckTime());
        dto.setUsedDomainCount((int) domainRepository.countByDnsProviderConfigId(config.getId()));
        dto.setRemark(config.getRemark());
        dto.setCreateTime(config.getCreateTime());
        dto.setUpdateTime(config.getUpdateTime());
        return dto;
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", dnsProviderConfigRepository.count());
        stats.put("enabled", dnsProviderConfigRepository.count("status", DnsProviderConfigStatus.ENABLED));
        stats.put("disabled", dnsProviderConfigRepository.count("status", DnsProviderConfigStatus.DISABLED));
        stats.put("cloudflare", dnsProviderConfigRepository.count("providerType", DnsProviderType.CLOUDFLARE));
        return stats;
    }

    public List<DomainDto> getBoundDomains(Long dnsProviderConfigId) {
        return domainRepository.findByDnsProviderConfigId(dnsProviderConfigId).stream()
                .map(domainService::convertToDto)
                .toList();
    }

    public List<DnsProviderConfigDto> getEnabledConfigs() {
        return dnsProviderConfigRepository.findByStatus(DnsProviderConfigStatus.ENABLED).stream()
                .map(this::convertToDto)
                .toList();
    }

    @Transactional
    public DnsProviderConfig save(DnsProviderConfigRequest request) {
        validateRequest(request, true);

        DnsProviderConfig config = new DnsProviderConfig();
        applyRequest(config, request, true);
        if (config.getStatus() == null) {
            config.setStatus(DnsProviderConfigStatus.ENABLED);
        }
        if (config.getLastCheckStatus() == null) {
            config.setLastCheckStatus(DnsProviderCheckStatus.NOT_CHECKED);
        }
        dnsProviderConfigRepository.persist(config);
        return config;
    }

    @Transactional
    public DnsProviderConfig update(Long id, DnsProviderConfigRequest request) {
        DnsProviderConfig existing = dnsProviderConfigRepository.findById(id);
        if (existing == null) {
            throw new EntityNotFoundException("DNS服务商不存在");
        }

        validateRequest(request, false);
        applyRequest(existing, request, false);
        return existing;
    }

    @Transactional
    public void delete(Long id) {
        DnsProviderConfig existing = dnsProviderConfigRepository.findById(id);
        if (existing == null) {
            throw new EntityNotFoundException("DNS服务商不存在");
        }

        long usedCount = domainRepository.countByDnsProviderConfigId(id);
        if (usedCount > 0) {
            throw new IllegalStateException("当前 DNS 服务商已绑定域名，不能删除");
        }

        dnsProviderConfigRepository.delete(existing);
    }

    @Transactional
    public DnsProviderConfig toggleStatus(Long id, DnsProviderConfigStatus status) {
        DnsProviderConfig existing = dnsProviderConfigRepository.findById(id);
        if (existing == null) {
            throw new EntityNotFoundException("DNS服务商不存在");
        }
        existing.setStatus(status);
        return existing;
    }

    @Transactional
    public DnsProviderTestResponse testConnection(Long id) {
        DnsProviderConfig existing = dnsProviderConfigRepository.findById(id);
        if (existing == null) {
            throw new EntityNotFoundException("DNS服务商不存在");
        }

        DnsProviderClient client = providerClientRegistry.getClient(existing.getProviderType());
        DnsProviderTestResponse result = client.testConnection(existing);
        existing.setLastCheckTime(result.getCheckTime());
        existing.setLastCheckStatus(result.getStatus());
        existing.setLastCheckMessage(result.getMessage());
        return result;
    }

    private void validateRequest(DnsProviderConfigRequest request, boolean creating) {
        if (request.getProviderType() == null) {
            throw new IllegalArgumentException("DNS 服务商类型不能为空");
        }
        if (request.getDisplayName() == null || request.getDisplayName().isBlank()) {
            throw new IllegalArgumentException("显示名称不能为空");
        }
        if (creating && (request.getApiToken() == null || request.getApiToken().isBlank())) {
            throw new IllegalArgumentException("API Token 不能为空");
        }
        if ((creating || (request.getApiToken() != null && !request.getApiToken().isBlank()))) {
            providerClientRegistry.getClient(request.getProviderType()).validateConfigRequest(request);
        }
    }

    private void applyRequest(DnsProviderConfig config, DnsProviderConfigRequest request, boolean creating) {
        config.setProviderType(request.getProviderType());
        config.setDisplayName(request.getDisplayName().trim());
        if (request.getStatus() != null) {
            config.setStatus(request.getStatus());
        } else if (creating) {
            config.setStatus(DnsProviderConfigStatus.ENABLED);
        }
        config.setRemark(normalizeBlank(request.getRemark()));

        String currentApiToken = extractJsonValue(config.getCredentialJson(), "apiToken");
        String apiToken = request.getApiToken() != null && !request.getApiToken().isBlank()
                ? request.getApiToken().trim()
                : currentApiToken;

        if (apiToken != null && !apiToken.isBlank()) {
            config.setCredentialJson(writeJson(Map.of("apiToken", apiToken)));
        }

        Map<String, Object> extensionMap = new LinkedHashMap<>();
        if (request.getAccountId() != null && !request.getAccountId().isBlank()) {
            extensionMap.put("accountId", request.getAccountId().trim());
        }
        config.setExtensionJson(extensionMap.isEmpty() ? null : writeJson(extensionMap));

        if (creating && config.getLastCheckStatus() == null) {
            config.setLastCheckStatus(DnsProviderCheckStatus.NOT_CHECKED);
        }
    }

    private String extractJsonValue(String json, String key) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> map = objectMapper.readValue(json, Map.class);
            Object value = map.get(key);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private String maskApiToken(String apiToken) {
        if (apiToken == null || apiToken.isBlank()) {
            return null;
        }
        if (apiToken.length() <= 8) {
            return "已配置";
        }
        return apiToken.substring(0, 4) + "****" + apiToken.substring(apiToken.length() - 4);
    }

    private String normalizeBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
