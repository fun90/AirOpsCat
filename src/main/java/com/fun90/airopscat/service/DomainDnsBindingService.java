package com.fun90.airopscat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.DomainDnsProviderBindingDto;
import com.fun90.airopscat.model.dto.DomainDnsProviderBindingRequest;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.model.entity.DnsProviderConfig;
import com.fun90.airopscat.model.enums.DnsProviderConfigStatus;
import com.fun90.airopscat.model.enums.DnsSyncStatus;
import com.fun90.airopscat.repository.DnsProviderConfigRepository;
import com.fun90.airopscat.repository.DomainDnsRecordRepository;
import com.fun90.airopscat.repository.DomainRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class DomainDnsBindingService {

    private final DomainRepository domainRepository;
    private final DnsProviderConfigRepository dnsProviderConfigRepository;
    private final DomainDnsRecordRepository domainDnsRecordRepository;
    private final ObjectMapper objectMapper;

    @Inject
    public DomainDnsBindingService(DomainRepository domainRepository,
                                   DnsProviderConfigRepository dnsProviderConfigRepository,
                                   DomainDnsRecordRepository domainDnsRecordRepository,
                                   ObjectMapper objectMapper) {
        this.domainRepository = domainRepository;
        this.dnsProviderConfigRepository = dnsProviderConfigRepository;
        this.domainDnsRecordRepository = domainDnsRecordRepository;
        this.objectMapper = objectMapper;
    }

    public DomainDnsProviderBindingDto getBinding(Long domainId) {
        Domain domain = requireDomain(domainId);
        return toDto(domain, resolveProvider(domain.getDnsProviderConfigId()));
    }

    @Transactional
    public DomainDnsProviderBindingDto bind(Long domainId, DomainDnsProviderBindingRequest request) {
        Domain domain = requireDomain(domainId);
        if (request.getDnsProviderConfigId() == null) {
            throw new IllegalArgumentException("DNS 服务商不能为空");
        }
        if (request.getZoneId() == null || request.getZoneId().isBlank()) {
            throw new IllegalArgumentException("Zone ID 不能为空");
        }

        DnsProviderConfig providerConfig = requireEnabledProvider(request.getDnsProviderConfigId());
        boolean bindingChanged = !request.getDnsProviderConfigId().equals(domain.getDnsProviderConfigId())
                || !request.getZoneId().trim().equals(extractZoneId(domain));

        domain.setDnsProviderConfigId(providerConfig.getId());
        domain.setDnsProviderType(providerConfig.getProviderType());
        domain.setDnsBindingExtensionJson(writeBindingJson(Map.of("zoneId", request.getZoneId().trim())));

        if (bindingChanged) {
            domainDnsRecordRepository.deleteByDomainId(domainId);
            domain.setDnsSyncStatus(DnsSyncStatus.NOT_SYNCED);
            domain.setDnsLastSyncTime(null);
        }

        return toDto(domain, providerConfig);
    }

    @Transactional
    public void unbind(Long domainId) {
        Domain domain = requireDomain(domainId);
        domainDnsRecordRepository.deleteByDomainId(domainId);
        domain.setDnsProviderConfigId(null);
        domain.setDnsProviderType(null);
        domain.setDnsBindingExtensionJson(null);
        domain.setDnsSyncStatus(null);
        domain.setDnsLastSyncTime(null);
    }

    private Domain requireDomain(Long domainId) {
        Domain domain = domainRepository.findById(domainId);
        if (domain == null) {
            throw new EntityNotFoundException("域名不存在");
        }
        return domain;
    }

    private DnsProviderConfig requireEnabledProvider(Long providerId) {
        DnsProviderConfig providerConfig = dnsProviderConfigRepository.findById(providerId);
        if (providerConfig == null) {
            throw new EntityNotFoundException("DNS 服务商不存在");
        }
        if (providerConfig.getStatus() != DnsProviderConfigStatus.ENABLED) {
            throw new IllegalStateException("DNS 服务商未启用，不能绑定");
        }
        return providerConfig;
    }

    private DnsProviderConfig resolveProvider(Long providerId) {
        if (providerId == null) {
            return null;
        }
        return dnsProviderConfigRepository.findById(providerId);
    }

    private DomainDnsProviderBindingDto toDto(Domain domain, DnsProviderConfig providerConfig) {
        DomainDnsProviderBindingDto dto = new DomainDnsProviderBindingDto();
        dto.setDomainId(domain.getId());
        dto.setDnsProviderConfigId(domain.getDnsProviderConfigId());
        dto.setDnsProviderType(domain.getDnsProviderType());
        dto.setDnsProviderName(providerConfig == null ? null : providerConfig.getDisplayName());
        dto.setZoneId(extractZoneId(domain));
        dto.setDnsSyncStatus(domain.getDnsSyncStatus());
        dto.setDnsLastSyncTime(domain.getDnsLastSyncTime());
        return dto;
    }

    private String writeBindingJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(value));
        } catch (Exception e) {
            throw new IllegalStateException("DNS 绑定配置序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractZoneId(Domain domain) {
        if (domain.getDnsBindingExtensionJson() == null || domain.getDnsBindingExtensionJson().isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(domain.getDnsBindingExtensionJson(), Map.class);
            Object value = map.get("zoneId");
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }
}
