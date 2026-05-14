package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.DnsProviderRecord;
import com.fun90.airopscat.model.dto.DomainDnsPullResponse;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.model.entity.DnsProviderConfig;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.enums.DnsProviderConfigStatus;
import com.fun90.airopscat.model.enums.DnsRecordStatus;
import com.fun90.airopscat.model.enums.DnsSyncStatus;
import com.fun90.airopscat.repository.AccountNodeSubscriptionDomainBindingRepository;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class DomainDnsPullService {

    private final DomainRepository domainRepository;
    private final DnsProviderConfigRepository dnsProviderConfigRepository;
    private final DomainDnsRecordRepository domainDnsRecordRepository;
    private final AccountNodeSubscriptionDomainBindingRepository bindingRepository;
    private final DnsProviderClientRegistry dnsProviderClientRegistry;

    @Inject
    public DomainDnsPullService(DomainRepository domainRepository,
                                DnsProviderConfigRepository dnsProviderConfigRepository,
                                DomainDnsRecordRepository domainDnsRecordRepository,
                                AccountNodeSubscriptionDomainBindingRepository bindingRepository,
                                DnsProviderClientRegistry dnsProviderClientRegistry) {
        this.domainRepository = domainRepository;
        this.dnsProviderConfigRepository = dnsProviderConfigRepository;
        this.domainDnsRecordRepository = domainDnsRecordRepository;
        this.bindingRepository = bindingRepository;
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

            LocalDateTime syncTime = LocalDateTime.now();
            boolean hasRetainedPendingDeleteRecords = syncRecords(domainId, providerConfig, records, syncTime);

            DnsSyncStatus syncStatus = hasRetainedPendingDeleteRecords ? DnsSyncStatus.NOT_SYNCED : DnsSyncStatus.SYNCED;
            domain.setDnsSyncStatus(syncStatus);
            domain.setDnsLastSyncTime(syncTime);

            DomainDnsPullResponse response = new DomainDnsPullResponse();
            response.setDomainId(domainId);
            response.setPulledRecordCount(records.size());
            response.setSyncStatus(syncStatus);
            response.setLastSyncTime(syncTime);
            response.setMessage(hasRetainedPendingDeleteRecords
                    ? "DNS 记录拉取成功，部分已绑定记录待解绑后删除"
                    : "DNS 记录拉取成功");
            return response;
        } catch (RuntimeException e) {
            domain.setDnsSyncStatus(DnsSyncStatus.SYNC_FAILED);
            throw e;
        }
    }

    private boolean syncRecords(Long domainId,
                                DnsProviderConfig providerConfig,
                                List<DnsProviderRecord> remoteRecords,
                                LocalDateTime syncTime) {
        List<DomainDnsRecord> localRecords = domainDnsRecordRepository.findByDomainId(domainId);
        Map<String, DomainDnsRecord> localRecordMap = new HashMap<>();
        for (DomainDnsRecord localRecord : localRecords) {
            if (hasText(localRecord.getExternalRecordId())) {
                localRecordMap.putIfAbsent(localRecord.getExternalRecordId(), localRecord);
            }
        }

        Set<Long> syncedRecordIds = new HashSet<>();
        for (DnsProviderRecord remoteRecord : remoteRecords) {
            DomainDnsRecord localRecord = hasText(remoteRecord.getExternalRecordId())
                    ? localRecordMap.remove(remoteRecord.getExternalRecordId())
                    : null;
            if (localRecord == null) {
                localRecord = new DomainDnsRecord();
                localRecord.setDomainId(domainId);
                domainDnsRecordRepository.persist(localRecord);
            } else {
                syncedRecordIds.add(localRecord.getId());
            }
            fillRecord(localRecord, providerConfig, remoteRecord, syncTime);
        }

        boolean hasRetainedPendingDeleteRecords = false;
        for (DomainDnsRecord missingRecord : localRecords) {
            if (syncedRecordIds.contains(missingRecord.getId())) {
                continue;
            }
            if (bindingRepository.existsByDomainDnsRecordId(missingRecord.getId())) {
                missingRecord.setStatus(DnsRecordStatus.PENDING_DELETE);
                missingRecord.setLastSyncTime(syncTime);
                hasRetainedPendingDeleteRecords = true;
            } else {
                domainDnsRecordRepository.delete(missingRecord);
            }
        }
        return hasRetainedPendingDeleteRecords;
    }

    private void fillRecord(DomainDnsRecord entity,
                            DnsProviderConfig providerConfig,
                            DnsProviderRecord record,
                            LocalDateTime syncTime) {
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
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
