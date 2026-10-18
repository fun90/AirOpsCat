package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.DomainDnsRecordBatchItemRequest;
import com.fun90.airopscat.model.dto.DomainDnsRecordBatchRequest;
import com.fun90.airopscat.model.dto.DomainDnsRecordDto;
import com.fun90.airopscat.model.dto.DomainDnsRecordRequest;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.enums.DnsRecordStatus;
import com.fun90.airopscat.model.enums.DnsSyncStatus;
import com.fun90.airopscat.repository.AccountNodeSubscriptionDomainBindingRepository;
import com.fun90.airopscat.repository.DomainDnsRecordRepository;
import com.fun90.airopscat.repository.DomainRepository;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class DomainDnsRecordService {

    private static final List<String> ALLOWED_SORT_FIELDS = List.of("type", "name", "content", "updateTime", "createTime");

    private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern HH_MM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    private final DomainDnsRecordRepository domainDnsRecordRepository;
    private final AccountNodeSubscriptionDomainBindingRepository bindingRepository;
    private final DomainRepository domainRepository;

    @Inject
    public DomainDnsRecordService(DomainDnsRecordRepository domainDnsRecordRepository,
                                  AccountNodeSubscriptionDomainBindingRepository bindingRepository,
                                  DomainRepository domainRepository) {
        this.domainDnsRecordRepository = domainDnsRecordRepository;
        this.bindingRepository = bindingRepository;
        this.domainRepository = domainRepository;
    }

    public PanacheQuery<DomainDnsRecord> getPage(Long domainId,
                                                 String search,
                                                 String type,
                                                 String sortBy,
                                                 String sortOrder) {
        requireBoundDomain(domainId);

        String effectiveSortBy = ALLOWED_SORT_FIELDS.contains(sortBy) ? sortBy : "type";
        Sort sort = "desc".equalsIgnoreCase(sortOrder)
                ? Sort.by(effectiveSortBy).descending().and("name")
                : Sort.by(effectiveSortBy).ascending().and("name");

        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new java.util.HashMap<>();
        conditions.add("domainId = :domainId");
        params.put("domainId", domainId);

        if (search != null && !search.isBlank()) {
            conditions.add("(lower(name) like :search or lower(content) like :search or lower(type) like :search)");
            params.put("search", "%" + search.trim().toLowerCase() + "%");
        }
        if (type != null && !type.isBlank()) {
            conditions.add("type = :type");
            params.put("type", type.trim().toUpperCase());
        }

        return domainDnsRecordRepository.find(String.join(" and ", conditions), sort, params);
    }

    public DomainDnsRecordDto convertToDto(DomainDnsRecord entity) {
        DomainDnsRecordDto dto = new DomainDnsRecordDto();
        dto.setId(entity.getId());
        dto.setDomainId(entity.getDomainId());
        dto.setDnsProviderConfigId(entity.getDnsProviderConfigId());
        dto.setExternalRecordId(entity.getExternalRecordId());
        dto.setName(entity.getName());
        dto.setFullName(entity.getFullName());
        dto.setType(entity.getType());
        dto.setContent(entity.getContent());
        dto.setTtl(entity.getTtl());
        dto.setProxied(entity.getProxied());
        dto.setPriority(entity.getPriority());
        dto.setStatus(entity.getStatus());
        dto.setRemark(entity.getRemark());
        dto.setScheduleEnabled(entity.getScheduleEnabled());
        dto.setScheduleStart(entity.getScheduleStart());
        dto.setScheduleEnd(entity.getScheduleEnd());
        dto.setScheduleContentIn(entity.getScheduleContentIn());
        dto.setScheduleContentOut(entity.getScheduleContentOut());
        dto.setBizTagsJson(entity.getBizTagsJson());
        dto.setExtensionJson(entity.getExtensionJson());
        dto.setRawData(entity.getRawData());
        dto.setLastSyncTime(entity.getLastSyncTime());
        dto.setCreateTime(entity.getCreateTime());
        dto.setUpdateTime(entity.getUpdateTime());
        return dto;
    }

    @Transactional
    public DomainDnsRecordDto create(Long domainId, DomainDnsRecordRequest request) {
        Domain domain = requireBoundDomain(domainId);
        validateRequest(request);

        DomainDnsRecord entity = new DomainDnsRecord();
        entity.setDomainId(domainId);
        entity.setDnsProviderConfigId(domain.getDnsProviderConfigId());
        entity.setName(request.getName().trim());
        entity.setFullName(buildFullName(domain, request));
        entity.setType(request.getType().trim().toUpperCase());
        applyContent(entity, request);
        entity.setTtl(request.getTtl());
        entity.setProxied(request.getProxied());
        entity.setPriority(request.getPriority());
        entity.setRemark(normalizeBlank(request.getRemark()));
        entity.setStatus(DnsRecordStatus.PENDING_CREATE);
        domainDnsRecordRepository.persist(entity);

        markDomainNotSynced(domain);
        return convertToDto(entity);
    }

    @Transactional
    public DomainDnsRecordDto update(Long domainId, Long recordId, DomainDnsRecordRequest request) {
        Domain domain = requireBoundDomain(domainId);
        validateRequest(request);
        DomainDnsRecord entity = requireRecord(domainId, recordId);

        entity.setName(request.getName().trim());
        entity.setFullName(buildFullName(domain, request));
        entity.setType(request.getType().trim().toUpperCase());
        applyContent(entity, request);
        entity.setTtl(request.getTtl());
        entity.setProxied(request.getProxied());
        entity.setPriority(request.getPriority());
        entity.setRemark(normalizeBlank(request.getRemark()));

        if (entity.getStatus() != DnsRecordStatus.PENDING_CREATE) {
            entity.setStatus(DnsRecordStatus.PENDING_UPDATE);
        }

        markDomainNotSynced(domain);
        return convertToDto(entity);
    }

    @Transactional
    public void delete(Long domainId, Long recordId) {
        Domain domain = requireBoundDomain(domainId);
        DomainDnsRecord entity = requireRecord(domainId, recordId);
        if (bindingRepository.existsByDomainDnsRecordId(recordId)) {
            throw new IllegalStateException("DNS 记录已被节点部署位或账户订阅域名引用，请先解绑后再删除");
        }

        if (entity.getStatus() == DnsRecordStatus.PENDING_CREATE) {
            domainDnsRecordRepository.delete(entity);
        } else {
            entity.setStatus(DnsRecordStatus.PENDING_DELETE);
        }

        markDomainNotSynced(domain);
    }

    @Transactional
    public void batch(Long domainId, DomainDnsRecordBatchRequest request) {
        Domain domain = requireBoundDomain(domainId);
        if (request == null || request.getAction() == null || request.getAction().isBlank()) {
            throw new IllegalArgumentException("批量操作类型不能为空");
        }
        List<DomainDnsRecordBatchItemRequest> items = request.getItems();
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("批量操作项不能为空");
        }

        switch (request.getAction().trim().toUpperCase()) {
            case "CREATE" -> batchCreate(domain, items);
            case "UPDATE" -> batchUpdate(domain, items);
            case "DELETE" -> batchDelete(domain, items);
            default -> throw new IllegalArgumentException("不支持的批量操作类型");
        }
    }

    private void batchCreate(Domain domain, List<DomainDnsRecordBatchItemRequest> items) {
        for (DomainDnsRecordBatchItemRequest item : items) {
            DomainDnsRecordRequest request = new DomainDnsRecordRequest();
            request.setName(item.getName());
            request.setType(item.getType());
            request.setContent(item.getContent());
            request.setTtl(item.getTtl());
            request.setProxied(item.getProxied());
            request.setPriority(item.getPriority());
            request.setRemark(item.getRemark());
            create(domain.getId(), request);
        }
    }

    private void batchUpdate(Domain domain, List<DomainDnsRecordBatchItemRequest> items) {
        for (DomainDnsRecordBatchItemRequest item : items) {
            if (item.getId() == null) {
                throw new IllegalArgumentException("批量更新时记录 ID 不能为空");
            }
            DomainDnsRecord entity = requireRecord(domain.getId(), item.getId());
            if (item.getName() != null && !item.getName().isBlank()) {
                entity.setName(item.getName().trim());
            }
            if (item.getType() != null && !item.getType().isBlank()) {
                entity.setType(item.getType().trim().toUpperCase());
            }
            if (item.getContent() != null && !item.getContent().isBlank()) {
                entity.setContent(item.getContent().trim());
            }
            if (item.getTtl() != null) {
                entity.setTtl(item.getTtl());
            }
            if (item.getProxied() != null) {
                entity.setProxied(item.getProxied());
            }
            if (item.getPriority() != null) {
                entity.setPriority(item.getPriority());
            }
            if (item.getRemark() != null) {
                entity.setRemark(normalizeBlank(item.getRemark()));
            }
            if (item.getName() != null && !item.getName().isBlank()) {
                entity.setFullName(buildFullName(domain, toRequest(entity, item)));
            }
            if (entity.getStatus() != DnsRecordStatus.PENDING_CREATE) {
                entity.setStatus(DnsRecordStatus.PENDING_UPDATE);
            }
        }
        markDomainNotSynced(domain);
    }

    private void batchDelete(Domain domain, List<DomainDnsRecordBatchItemRequest> items) {
        for (DomainDnsRecordBatchItemRequest item : items) {
            if (item.getId() == null) {
                throw new IllegalArgumentException("批量删除时记录 ID 不能为空");
            }
            delete(domain.getId(), item.getId());
        }
    }

    private void validateRequest(DomainDnsRecordRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("DNS 记录不能为空");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("记录名称不能为空");
        }
        if (request.getType() == null || request.getType().isBlank()) {
            throw new IllegalArgumentException("记录类型不能为空");
        }
        if (!Boolean.TRUE.equals(request.getScheduleEnabled())
                && (request.getContent() == null || request.getContent().isBlank())) {
            throw new IllegalArgumentException("记录内容不能为空");
        }
    }

    /** 启用时段计划时 content 由计划决定，忽略请求中的 content */
    private void applyContent(DomainDnsRecord entity, DomainDnsRecordRequest request) {
        boolean scheduled = Boolean.TRUE.equals(request.getScheduleEnabled());
        entity.setScheduleEnabled(scheduled);
        if (!scheduled) {
            entity.setContent(request.getContent().trim());
            return;
        }
        if (!isHhMm(request.getScheduleStart()) || !isHhMm(request.getScheduleEnd())
                || request.getScheduleStart().equals(request.getScheduleEnd())) {
            throw new IllegalArgumentException("时段起止时间需为不同的 HH:mm");
        }
        if (isBlank(request.getScheduleContentIn()) || isBlank(request.getScheduleContentOut())) {
            throw new IllegalArgumentException("时段内与时段外的记录内容均不能为空");
        }
        entity.setScheduleStart(request.getScheduleStart());
        entity.setScheduleEnd(request.getScheduleEnd());
        entity.setScheduleContentIn(request.getScheduleContentIn().trim());
        entity.setScheduleContentOut(request.getScheduleContentOut().trim());
        entity.setContent(entity.scheduledContent(LocalTime.now(SCHEDULE_ZONE)));
    }

    private boolean isHhMm(String value) {
        return value != null && HH_MM.matcher(value).matches();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 按当前时间刷新所有时段计划记录的内容，返回需要推送的域名 ID */
    @Transactional
    public Set<Long> applySchedules() {
        LocalTime now = LocalTime.now(SCHEDULE_ZONE);
        Set<Long> domainIds = new HashSet<>();
        for (DomainDnsRecord record : domainDnsRecordRepository.findScheduled()) {
            String target = record.scheduledContent(now);
            boolean changed = !target.equals(record.getContent());
            if (changed) {
                record.setContent(target);
                if (record.getStatus() != DnsRecordStatus.PENDING_CREATE) {
                    record.setStatus(DnsRecordStatus.PENDING_UPDATE);
                }
            }
            // 上次推送失败的 PENDING_UPDATE 也要重试
            if (changed || record.getStatus() == DnsRecordStatus.PENDING_UPDATE) {
                domainIds.add(record.getDomainId());
            }
        }
        for (Long domainId : domainIds) {
            Domain domain = domainRepository.findById(domainId);
            if (domain != null) {
                markDomainNotSynced(domain);
            }
        }
        return domainIds;
    }

    private Domain requireBoundDomain(Long domainId) {
        Domain domain = domainRepository.findById(domainId);
        if (domain == null) {
            throw new EntityNotFoundException("域名不存在");
        }
        if (domain.getDnsProviderConfigId() == null) {
            throw new IllegalStateException("当前域名尚未绑定 DNS 服务商");
        }
        return domain;
    }

    private DomainDnsRecord requireRecord(Long domainId, Long recordId) {
        DomainDnsRecord entity = domainDnsRecordRepository.findById(recordId);
        if (entity == null || !domainId.equals(entity.getDomainId())) {
            throw new EntityNotFoundException("DNS 记录不存在");
        }
        return entity;
    }

    private void markDomainNotSynced(Domain domain) {
        domain.setDnsSyncStatus(DnsSyncStatus.NOT_SYNCED);
    }

    private String normalizeBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String buildFullName(Domain domain, DomainDnsRecordRequest request) {
        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            return request.getFullName().trim();
        }
        String name = request.getName().trim();
        if ("@".equals(name) || domain.getDomain().equalsIgnoreCase(name)) {
            return domain.getDomain();
        }
        if (name.endsWith("." + domain.getDomain())) {
            return name;
        }
        return name + "." + domain.getDomain();
    }

    private DomainDnsRecordRequest toRequest(DomainDnsRecord entity, DomainDnsRecordBatchItemRequest item) {
        DomainDnsRecordRequest request = new DomainDnsRecordRequest();
        request.setName(item.getName() != null && !item.getName().isBlank() ? item.getName() : entity.getName());
        request.setFullName(null);
        return request;
    }
}
