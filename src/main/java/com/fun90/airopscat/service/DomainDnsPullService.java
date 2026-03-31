package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.DnsProviderRecord;
import com.fun90.airopscat.model.dto.DomainDnsPullResponse;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.model.entity.DnsProviderConfig;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.enums.DnsProviderConfigStatus;
import com.fun90.airopscat.model.enums.DnsRecordStatus;
import com.fun90.airopscat.model.enums.DnsSyncStatus;
import com.fun90.airopscat.repository.DnsProviderConfigRepository;
import com.fun90.airopscat.repository.DomainDnsRecordRepository;
import com.fun90.airopscat.repository.DomainRepository;
import com.fun90.airopscat.service.dns.DnsProviderClient;
import com.fun90.airopscat.service.dns.DnsProviderClientRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class DomainDnsPullService {

    private final DomainRepository domainRepository;
    private final DnsProviderConfigRepository dnsProviderConfigRepository;
    private final DomainDnsRecordRepository domainDnsRecordRepository;
    private final DnsProviderClientRegistry dnsProviderClientRegistry;

    @Inject
    public DomainDnsPullService(DomainRepository domainRepository,
                                DnsProviderConfigRepository dnsProviderConfigRepository,
                                DomainDnsRecordRepository domainDnsRecordRepository,
                                DnsProviderClientRegistry dnsProviderClientRegistry) {
        this.domainRepository = domainRepository;
        this.dnsProviderConfigRepository = dnsProviderConfigRepository;
        this.domainDnsRecordRepository = domainDnsRecordRepository;
        this.dnsProviderClientRegistry = dnsProviderClientRegistry;
    }

    @Transactional
    public DomainDnsPullResponse pull(Long domainId) {
        Domain domain = domainRepository.findById(domainId);
        if (domain == null) {
            throw new EntityNotFoundException("域名不存在");
        }
        if (domain.getDnsProviderConfigId() == null) {
            throw new IllegalStateException("当前域名尚未绑定 DNS 服务商");
        }

        DnsProviderConfig providerConfig = dnsProviderConfigRepository.findById(domain.getDnsProviderConfigId());
        if (providerConfig == null) {
            throw new EntityNotFoundException("DNS 服务商不存在");
        }
        if (providerConfig.getStatus() != DnsProviderConfigStatus.ENABLED) {
            throw new IllegalStateException("当前 DNS 服务商未启用");
        }

        domain.setDnsSyncStatus(DnsSyncStatus.PULLING);

        try {
            DnsProviderClient providerClient = dnsProviderClientRegistry.getClient(providerConfig.getProviderType());
            List<DnsProviderRecord> records = providerClient.listRecords(providerConfig, domain);

            domainDnsRecordRepository.deleteByDomainId(domainId);
            LocalDateTime syncTime = LocalDateTime.now();

            for (DnsProviderRecord record : records) {
                DomainDnsRecord entity = new DomainDnsRecord();
                entity.setDomainId(domainId);
                entity.setDnsProviderConfigId(providerConfig.getId());
                entity.setExternalRecordId(record.getExternalRecordId());
                entity.setName(record.getName());
                entity.setFullName(record.getFullName());
                entity.setType(record.getType());
                entity.setContent(record.getContent());
                entity.setTtl(record.getTtl());
                entity.setProxied(record.getProxied());
                entity.setPriority(record.getPriority());
                entity.setStatus(DnsRecordStatus.SYNCED);
                entity.setExtensionJson(record.getExtensionJson());
                entity.setRawData(record.getRawData());
                entity.setLastSyncTime(syncTime);
                domainDnsRecordRepository.persist(entity);
            }

            domain.setDnsSyncStatus(DnsSyncStatus.SYNCED);
            domain.setDnsLastSyncTime(syncTime);

            DomainDnsPullResponse response = new DomainDnsPullResponse();
            response.setDomainId(domainId);
            response.setPulledRecordCount(records.size());
            response.setSyncStatus(DnsSyncStatus.SYNCED);
            response.setLastSyncTime(syncTime);
            response.setMessage("DNS 记录拉取成功");
            return response;
        } catch (RuntimeException e) {
            domain.setDnsSyncStatus(DnsSyncStatus.SYNC_FAILED);
            throw e;
        }
    }
}
