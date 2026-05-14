package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.AccountNodeSubscriptionDomainBinding;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class AccountNodeSubscriptionDomainBindingRepository implements PanacheRepository<AccountNodeSubscriptionDomainBinding> {

    public List<AccountNodeSubscriptionDomainBinding> findByAccountId(Long accountId) {
        if (accountId == null) {
            return List.of();
        }
        return find("accountId", accountId).list();
    }

    public List<AccountNodeSubscriptionDomainBinding> findByNodeId(Long nodeId) {
        if (nodeId == null) {
            return List.of();
        }
        return find("nodeId", nodeId).list();
    }

    public Optional<AccountNodeSubscriptionDomainBinding> findByAccountIdAndNodeId(Long accountId, Long nodeId) {
        if (accountId == null || nodeId == null) {
            return Optional.empty();
        }
        return find("accountId = ?1 and nodeId = ?2", accountId, nodeId).firstResultOptional();
    }

    public List<AccountNodeSubscriptionDomainBinding> findEnabledByAccountIdAndNodeIds(Long accountId, List<Long> nodeIds) {
        if (accountId == null || nodeIds == null || nodeIds.isEmpty()) {
            return List.of();
        }
        return find("accountId = ?1 and nodeId in ?2 and enabled = 1", accountId, nodeIds).list();
    }
}
