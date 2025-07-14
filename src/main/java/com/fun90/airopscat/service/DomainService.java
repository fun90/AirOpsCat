package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.DomainDto;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.repository.DomainRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@ApplicationScoped
public class DomainService {

    private final DomainRepository domainRepository;

    @Inject
    public DomainService(DomainRepository domainRepository) {
        this.domainRepository = domainRepository;
    }

    public io.quarkus.hibernate.orm.panache.PanacheQuery<Domain> getDomainPage(String search, LocalDate expiryFrom, LocalDate expiryTo) {
        // Create sort by expireDate ascending
        Sort sort = Sort.by("expireDate").ascending();
        
        // Build query string
        StringBuilder queryBuilder = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        
        List<String> conditions = new ArrayList<>();
        
        // Search condition
        if (StringUtils.isNotBlank(search)) {
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

    public Optional<Domain> getByDomainName(String domain) {
        return domainRepository.findByDomain(domain);
    }

    public List<Domain> getExpiringDomains(int days) {
        LocalDate expiryDate = LocalDate.now().plusDays(days);
        return domainRepository.findExpiringDomains(expiryDate);
    }

    public List<Domain> getDomainsExpiringBetween(LocalDate startDate, LocalDate endDate) {
        return domainRepository.findDomainsExpiringBetween(startDate, endDate);
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
    
    // 获取域名状态描述
    public String getDomainStatusDescription(LocalDate expireDate) {
        if (expireDate == null) {
            return "未设置到期日";
        }
        
        LocalDate today = LocalDate.now();
        long daysUntilExpiration = ChronoUnit.DAYS.between(today, expireDate);
        
        if (daysUntilExpiration < 0) {
            return "已过期 " + Math.abs(daysUntilExpiration) + " 天";
        } else if (daysUntilExpiration == 0) {
            return "今天到期";
        } else if (daysUntilExpiration <= 30) {
            return "即将到期 " + daysUntilExpiration + " 天";
        } else {
            return "正常 (还有 " + daysUntilExpiration + " 天)";
        }
    }
    
    // 获取域名状态类型（用于前端展示不同颜色）
    public String getDomainStatusType(LocalDate expireDate) {
        if (expireDate == null) {
            return "warning";
        }
        
        LocalDate today = LocalDate.now();
        long daysUntilExpiration = ChronoUnit.DAYS.between(today, expireDate);
        
        if (daysUntilExpiration < 0) {
            return "danger";
        } else if (daysUntilExpiration <= 30) {
            return "warning";
        } else {
            return "success";
        }
    }
}