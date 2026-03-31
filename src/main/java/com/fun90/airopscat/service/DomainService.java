package com.fun90.airopscat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.DomainDto;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.model.entity.DnsProviderConfig;
import com.fun90.airopscat.repository.DnsProviderConfigRepository;
import com.fun90.airopscat.repository.DomainRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class DomainService {

    private final DomainRepository domainRepository;
    private final DnsProviderConfigRepository dnsProviderConfigRepository;
    private final ObjectMapper objectMapper;

    @Inject
    public DomainService(DomainRepository domainRepository,
                         DnsProviderConfigRepository dnsProviderConfigRepository,
                         ObjectMapper objectMapper) {
        this.domainRepository = domainRepository;
        this.dnsProviderConfigRepository = dnsProviderConfigRepository;
        this.objectMapper = objectMapper;
    }

    public io.quarkus.hibernate.orm.panache.PanacheQuery<Domain> getDomainPage(String search, LocalDate expiryFrom, LocalDate expiryTo) {
        // Create sort by expireDate ascending
        Sort sort = Sort.by("expireDate").ascending();
        
        // Build query string
        Map<String, Object> params = new HashMap<>();
        
        List<String> conditions = new ArrayList<>();
        
        // Search condition
        if (search != null && !search.trim().isEmpty()) {
            conditions.add("(lower(domain) like :search or lower(remark) like :search)");
            params.put("search", "%" + search.toLowerCase() + "%");
        }
        
        // Expiry date range filters
        if (expiryFrom != null) {
            conditions.add("expireDate >= :expiryFrom");
            params.put("expiryFrom", expiryFrom);
        }
        
        if (expiryTo != null) {
            conditions.add("expireDate <= :expiryTo");
            params.put("expiryTo", expiryTo);
        }
        
        String query = conditions.isEmpty() ? "" : String.join(" and ", conditions);
        
        if (query.isEmpty()) {
            return domainRepository.findAll(sort);
        } else {
            return domainRepository.find(query, sort, params);
        }
    }

    public Domain getDomainById(Long id) {
        return domainRepository.findById(id);
    }

    public List<Domain> getExpiringDomains(int days) {
        LocalDate expiryDate = LocalDate.now().plusDays(days);
        return domainRepository.findExpiringDomains(expiryDate);
    }

    public Long countExpiredDomains() {
        return domainRepository.countExpiredDomains(LocalDate.now());
    }

    public Long countExpiringInOneMonth() {
        LocalDate today = LocalDate.now();
        LocalDate inOneMonth = today.plusMonths(1);
        return domainRepository.countExpiringInOneMonth(today, inOneMonth);
    }

    public BigDecimal getTotalDomainCost() {
        BigDecimal total = domainRepository.getTotalDomainCost();
        return total != null ? total : BigDecimal.ZERO;
    }

    public DomainDto convertToDto(Domain domain) {
        DomainDto dto = new DomainDto();
        
        // Copy properties manually
        dto.setId(domain.getId());
        dto.setDomain(domain.getDomain());
        dto.setPrice(domain.getPrice());
        dto.setExpireDate(domain.getExpireDate());
        dto.setSupplier(domain.getSupplier());
        dto.setCreateTime(domain.getCreateTime());
        dto.setUpdateTime(domain.getUpdateTime());
        dto.setRemark(domain.getRemark());
        dto.setDnsProviderConfigId(domain.getDnsProviderConfigId());
        dto.setDnsProviderType(domain.getDnsProviderType());
        dto.setDnsSyncStatus(domain.getDnsSyncStatus());
        dto.setDnsLastSyncTime(domain.getDnsLastSyncTime());
        dto.setDnsZoneId(extractZoneId(domain));

        if (domain.getDnsProviderConfigId() != null) {
            DnsProviderConfig dnsProviderConfig = dnsProviderConfigRepository.findById(domain.getDnsProviderConfigId());
            if (dnsProviderConfig != null) {
                dto.setDnsProviderName(dnsProviderConfig.getDisplayName());
            }
        }
        
        // Calculate days until expiration
        if (domain.getExpireDate() != null) {
            LocalDate today = LocalDate.now();
            dto.setDaysUntilExpiration(ChronoUnit.DAYS.between(today, domain.getExpireDate()));
        }
        
        return dto;
    }

    @Transactional
    public Domain saveDomain(Domain domain) {
        domainRepository.persist(domain);
        return domain;
    }

    @Transactional
    public Domain updateDomain(Domain domain) {
        Domain existingDomain = domainRepository.findById(domain.getId());
        if (existingDomain == null) {
            throw new EntityNotFoundException("Domain not found");
        }

        // Copy non-null properties manually
        copyNonNullProperties(domain, existingDomain);

        // No need to call save/persist for updates in Panache
        return existingDomain;
    }

    // 工具方法：复制非null属性
    private void copyNonNullProperties(Domain src, Domain target) {
        if (src.getDomain() != null) target.setDomain(src.getDomain());
        if (src.getPrice() != null) target.setPrice(src.getPrice());
        if (src.getExpireDate() != null) target.setExpireDate(src.getExpireDate());
        if (src.getSupplier() != null) target.setSupplier(src.getSupplier());
        if (src.getRemark() != null) target.setRemark(src.getRemark());
    }

    @Transactional
    public void deleteDomain(Long id) {
        domainRepository.deleteById(id);
    }

    @Transactional
    public Domain renewDomain(Long id, LocalDate newExpiryDate) {
        Domain domain = domainRepository.findById(id);
        if (domain == null) {
            throw new EntityNotFoundException("Domain not found");
        }

        domain.setExpireDate(newExpiryDate);
        return domain;
    }

    @SuppressWarnings("unchecked")
    private String extractZoneId(Domain domain) {
        if (domain.getDnsBindingExtensionJson() == null || domain.getDnsBindingExtensionJson().isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(domain.getDnsBindingExtensionJson(), Map.class);
            Object zoneId = map.get("zoneId");
            return zoneId == null ? null : String.valueOf(zoneId);
        } catch (Exception e) {
            return null;
        }
    }

}
