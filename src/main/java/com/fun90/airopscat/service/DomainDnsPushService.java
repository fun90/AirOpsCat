package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.DnsBatchChangeItem;
import com.fun90.airopscat.model.dto.DnsBatchChangeRequest;
import com.fun90.airopscat.model.dto.DnsBatchChangeResponse;
import com.fun90.airopscat.model.dto.DomainDnsPullResponse;
import com.fun90.airopscat.model.dto.DomainDnsPushResponse;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.entity.DnsProviderConfig;
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

import java.util.List;

@ApplicationScoped
public class DomainDnsPushService {

    private final DomainRepository domainRepository;
    private final DnsProviderConfigRepository dnsProviderConfigRepository;
    private final DomainDnsRecordRepository domainDnsRecordRepository;
    private final DnsProviderClientRegistry dnsProviderClientRegistry;
    private final DomainDnsPullService domainDnsPullService;

    @Inject
    public DomainDnsPushService(DomainRepository domainRepository,
                                DnsProviderConfigRepository dnsProviderConfigRepository,
                                DomainDnsRecordRepository domainDnsRecordRepository,
                                DnsProviderClientRegistry dnsProviderClientRegistry,
                                DomainDnsPullService domainDnsPullService) {
        this.domainRepository = domainRepository;
        this.dnsProviderConfigRepository = dnsProviderConfigRepository;
        this.domainDnsRecordRepository = domainDnsRecordRepository;
        this.dnsProviderClientRegistry = dnsProviderClientRegistry;
        this.domainDnsPullService = domainDnsPullService;
    }

    @Transactional
    public DomainDnsPushResponse push(Long domainId) {
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

        List<DomainDnsRecord> pendingRecords = domainDnsRecordRepository.findPendingByDomainId(domainId);
        if (pendingRecords.isEmpty()) {
            DomainDnsPushResponse response = new DomainDnsPushResponse();
            response.setDomainId(domainId);
            response.setSyncStatus(domain.getDnsSyncStatus() == null ? DnsSyncStatus.SYNCED : domain.getDnsSyncStatus());
            response.setLastSyncTime(domain.getDnsLastSyncTime());
            response.setMessage("当前没有待推送的 DNS 记录");
            return response;
        }

        domain.setDnsSyncStatus(DnsSyncStatus.PUSHING);

        try {
            DnsBatchChangeRequest request = buildBatchRequest(pendingRecords);
            DnsProviderClient providerClient = dnsProviderClientRegistry.getClient(providerConfig.getProviderType());
            DnsBatchChangeResponse batchResponse = providerClient.batchChangeRecords(providerConfig, domain, request);
            DomainDnsPullResponse pullResponse = domainDnsPullService.pull(domainId);

            DomainDnsPushResponse response = new DomainDnsPushResponse();
            response.setDomainId(domainId);
            response.setPushedRecordCount(batchResponse.getDeletedCount() + batchResponse.getPatchedCount() + batchResponse.getPostedCount());
            response.setDeletedCount(batchResponse.getDeletedCount());
            response.setUpdatedCount(batchResponse.getPatchedCount());
            response.setCreatedCount(batchResponse.getPostedCount());
            response.setSyncStatus(pullResponse.getSyncStatus());
            response.setLastSyncTime(pullResponse.getLastSyncTime());
            response.setMessage("DNS 记录推送成功，并已自动刷新本地快照");
            return response;
        } catch (RuntimeException e) {
            domain.setDnsSyncStatus(DnsSyncStatus.SYNC_FAILED);
            throw e;
        }
    }

    private DnsBatchChangeRequest buildBatchRequest(List<DomainDnsRecord> pendingRecords) {
        DnsBatchChangeRequest request = new DnsBatchChangeRequest();
        for (DomainDnsRecord record : pendingRecords) {
            DnsBatchChangeItem item = toBatchItem(record);
            if (record.getStatus() == DnsRecordStatus.PENDING_CREATE) {
                request.getPosts().add(item);
            } else if (record.getStatus() == DnsRecordStatus.PENDING_UPDATE) {
                if (record.getExternalRecordId() == null || record.getExternalRecordId().isBlank()) {
                    request.getPosts().add(item);
                } else {
                    request.getPatches().add(item);
                }
            } else if (record.getStatus() == DnsRecordStatus.PENDING_DELETE) {
                if (record.getExternalRecordId() != null && !record.getExternalRecordId().isBlank()) {
                    request.getDeletes().add(item);
                }
            }
        }
        return request;
    }

    private DnsBatchChangeItem toBatchItem(DomainDnsRecord record) {
        DnsBatchChangeItem item = new DnsBatchChangeItem();
        item.setId(record.getId());
        item.setExternalRecordId(record.getExternalRecordId());
        item.setName(record.getName());
        item.setFullName(record.getFullName());
        item.setType(record.getType());
        item.setContent(record.getContent());
        item.setTtl(record.getTtl());
        item.setProxied(record.getProxied());
        item.setPriority(record.getPriority());
        item.setExtensionJson(record.getExtensionJson());
        return item;
    }
}
