package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.DnsProviderConfig;
import com.fun90.airopscat.model.enums.DnsProviderConfigStatus;
import com.fun90.airopscat.model.enums.DnsProviderType;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class DnsProviderConfigRepository implements PanacheRepository<DnsProviderConfig> {

    public List<DnsProviderConfig> findByStatus(DnsProviderConfigStatus status) {
        return find("status", status).list();
    }

    public List<DnsProviderConfig> findByProviderType(DnsProviderType providerType) {
        return find("providerType", providerType).list();
    }

    public List<DnsProviderConfig> findByIdIn(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return find("id in ?1", ids).list();
    }
}
