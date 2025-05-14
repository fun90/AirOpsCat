package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.DomainDto;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.repository.DomainRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.beans.PropertyDescriptor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class DomainService {

    private final DomainRepository domainRepository;

    @Autowired
    public DomainService(DomainRepository domainRepository) {
        this.domainRepository = domainRepository;
    }

    public Page<Domain> getDomainPage(int page, int size, String search, LocalDate expiryFrom, LocalDate expiryTo) {
        // Create pageable with sorting (expiry date first)
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("expireDate").ascending());

        // Create specification for dynamic filtering
        Specification<Domain> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Search in domain and remark
            if (StringUtils.hasText(search)) {
                Predicate domainPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("domain")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate remarkPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("remark")),
                        "%" + search.toLowerCase() + "%"
                );
                predicates.add(criteriaBuilder.or(domainPredicate, remarkPredicate));
            }

            // Filter by expiry range
            if (expiryFrom != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("expireDate"), expiryFrom));
            }
            
            if (expiryTo != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("expireDate"), expiryTo));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        return domainRepository.findAll(spec, pageable);
    }

    public Domain getDomainById(Long id) {
        return domainRepository.findById(id).orElse(null);
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
        BeanUtils.copyProperties(domain, dto);
        
        // Calculate days until expiration
        if (domain.getExpireDate() != null) {
            LocalDate today = LocalDate.now();
            dto.setDaysUntilExpiration(ChronoUnit.DAYS.between(today, domain.getExpireDate()));
        }
        
        return dto;
    }

    @Transactional
    public Domain saveDomain(Domain domain) {
        return domainRepository.save(domain);
    }

    @Transactional
    public Domain updateDomain(Domain domain) {
        Domain existingDomain = domainRepository.findById(domain.getId())
                .orElseThrow(() -> new EntityNotFoundException("Domain not found"));

        // 使用工具方法复制非null属性
        copyNonNullProperties(domain, existingDomain);

        return domainRepository.save(existingDomain);
    }

    // 工具方法：复制非null属性
    private void copyNonNullProperties(Object src, Object target) {
        BeanUtils.copyProperties(src, target, getNullPropertyNames(src));
    }

    // 获取对象中所有为null的属性名
    private String[] getNullPropertyNames(Object source) {
        final BeanWrapper src = new BeanWrapperImpl(source);
        PropertyDescriptor[] pds = src.getPropertyDescriptors();

        Set<String> nullNames = new HashSet<>();
        for (PropertyDescriptor pd : pds) {
            Object srcValue = src.getPropertyValue(pd.getName());
            if (srcValue == null) {
                nullNames.add(pd.getName());
            }
        }
        return nullNames.toArray(new String[0]);
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