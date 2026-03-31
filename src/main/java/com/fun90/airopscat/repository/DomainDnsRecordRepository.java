package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.enums.DnsRecordStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class DomainDnsRecordRepository implements PanacheRepository<DomainDnsRecord> {

    public List<DomainDnsRecord> findByDomainId(Long domainId) {
        return find("domainId", domainId).list();
    }

    public void deleteByDomainId(Long domainId) {
        delete("domainId", domainId);
    }

    public List<DomainDnsRecord> findByDnsProviderConfigId(Long dnsProviderConfigId) {
        return find("dnsProviderConfigId", dnsProviderConfigId).list();
    }

    public DomainDnsRecord findByExternalRecordId(String externalRecordId) {
        return find("externalRecordId", externalRecordId).firstResult();
    }

    public List<DomainDnsRecord> findByDomainIdAndStatus(Long domainId, DnsRecordStatus status) {
        return find("domainId = ?1 and status = ?2", domainId, status).list();
    }

    public List<DomainDnsRecord> findPendingByDomainId(Long domainId) {
        return list("domainId = ?1 and status in (?2, ?3, ?4)",
                domainId,
                DnsRecordStatus.PENDING_CREATE,
                DnsRecordStatus.PENDING_UPDATE,
                DnsRecordStatus.PENDING_DELETE);
    }
}
