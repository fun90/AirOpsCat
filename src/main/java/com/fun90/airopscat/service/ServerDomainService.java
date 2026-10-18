package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.DomainDnsRecordRequest;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerHost;
import com.fun90.airopscat.model.enums.DnsRecordStatus;
import com.fun90.airopscat.repository.AccountNodeSubscriptionDomainBindingRepository;
import com.fun90.airopscat.repository.DomainDnsRecordRepository;
import com.fun90.airopscat.repository.NodeServerRepository;
import com.fun90.airopscat.repository.ServerHostRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 服务器绑定的域名：即 server_id 指向该服务器的 DNS 记录。
 */
@Slf4j
@ApplicationScoped
public class ServerDomainService {

    /** 事务提交后推送该域名的待推送记录 */
    public record DnsPushRequested(Long domainId) {}

    private static final java.util.regex.Pattern SUBDOMAIN = java.util.regex.Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$");

    @Inject
    DomainDnsRecordRepository domainDnsRecordRepository;

    @Inject
    ServerHostRepository serverHostRepository;

    @Inject
    NodeServerRepository nodeServerRepository;

    @Inject
    AccountNodeSubscriptionDomainBindingRepository bindingRepository;

    @Inject
    DomainDnsRecordService domainDnsRecordService;

    @Inject
    DomainDnsPushService domainDnsPushService;

    @Inject
    Event<DnsPushRequested> pushRequested;

    /**
     * 选择已有的 DNS 记录并绑定到服务器。
     *
     * @param excludePlacementId 当前部署位自身的引用不算占用
     * @param excludeBindingId 当前账户订阅定制自身的引用不算占用
     */
    @Transactional
    public DomainDnsRecord bindExisting(Long recordId, Server server, Long excludePlacementId, Long excludeBindingId) {
        DomainDnsRecord record = domainDnsRecordRepository.findById(recordId);
        if (record == null) {
            throw new IllegalArgumentException("所选域名记录不存在");
        }
        String name = record.getFullName();
        if (!"A".equalsIgnoreCase(record.getType())) {
            throw new IllegalArgumentException("域名 " + name + " 不是 A 记录，不能绑定到服务器");
        }
        if (record.getStatus() == DnsRecordStatus.PENDING_DELETE) {
            throw new IllegalArgumentException("域名 " + name + " 已标记删除");
        }
        if (Boolean.TRUE.equals(record.getScheduleEnabled())) {
            throw new IllegalArgumentException("域名 " + name + " 开启了时段计划，不能绑定到服务器");
        }
        if (nodeServerRepository.existsByAccessHostIdAndIdNot(recordId, excludePlacementId)
                || bindingRepository.count("domainDnsRecordId = ?1 and id <> ?2", recordId,
                excludeBindingId == null ? -1L : excludeBindingId) > 0) {
            throw new IllegalArgumentException("域名 " + name + " 已被其他部署位或账户订阅定制使用");
        }
        if (record.getServerId() == null && !java.util.Objects.equals(record.getContent(), server.getIp())) {
            throw new IllegalArgumentException("域名 " + name + " 当前解析到 " + record.getContent()
                    + "，与服务器 IP " + server.getIp() + " 不一致，请先在域名页修改");
        }
        record.setServerId(server.getId());
        return record;
    }

    /** 可选给该服务器部署位使用的已有 A 记录：已绑定本服务器，或未绑定且解析到本服务器 IP；排除已被引用的 */
    public List<Map<String, Object>> getAvailableRecords(Server server) {
        List<DomainDnsRecord> candidates = domainDnsRecordRepository.list(
                "upper(type) = 'A' and status <> ?1 and (scheduleEnabled is null or scheduleEnabled = false)"
                        + " and (serverId = ?2 or (serverId is null and content = ?3)) order by fullName",
                DnsRecordStatus.PENDING_DELETE, server.getId(), server.getIp());
        List<Map<String, Object>> result = new ArrayList<>();
        for (DomainDnsRecord record : candidates) {
            if (nodeServerRepository.existsByAccessHostIdAndIdNot(record.getId(), -1L)
                    || bindingRepository.count("domainDnsRecordId", record.getId()) > 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", record.getId());
            item.put("fullName", record.getFullName());
            result.add(item);
        }
        return result;
    }

    /**
     * 新建指向服务器 IP 的 A 记录并绑定。记录先以待创建状态入库，事务提交后再推送到 DNS 服务商。
     */
    @Transactional
    public DomainDnsRecord createARecord(Long domainId, String subdomain, Server server) {
        String name = subdomain == null ? "" : subdomain.trim().toLowerCase();
        if (domainId == null || !SUBDOMAIN.matcher(name).matches()) {
            throw new IllegalArgumentException("新建域名需选择域名并填写合法的子域名");
        }
        DomainDnsRecordRequest request = new DomainDnsRecordRequest();
        request.setName(name);
        request.setType("A");
        request.setContent(server.getIp());
        request.setTtl(1);
        request.setProxied(false);
        Long recordId = domainDnsRecordService.create(domainId, request).getId();
        DomainDnsRecord record = domainDnsRecordRepository.findById(recordId);
        if (domainDnsRecordRepository.count("domainId = ?1 and fullName = ?2 and id <> ?3",
                domainId, record.getFullName(), recordId) > 0) {
            throw new IllegalArgumentException("已存在同名记录 " + record.getFullName() + "，请选择已有记录");
        }
        record.setServerId(server.getId());
        pushRequested.fire(new DnsPushRequested(domainId));
        return record;
    }

    /** 记录不再被任何部署位或账户订阅定制引用时解除服务器绑定，可再被选用 */
    @Transactional
    public void releaseIfUnused(Long recordId) {
        if (recordId == null || nodeServerRepository.existsByAccessHostIdAndIdNot(recordId, -1L)
                || bindingRepository.count("domainDnsRecordId", recordId) > 0) {
            return;
        }
        DomainDnsRecord record = domainDnsRecordRepository.findById(recordId);
        if (record != null) {
            record.setServerId(null);
        }
    }

    void onPushRequested(@Observes(during = TransactionPhase.AFTER_SUCCESS) DnsPushRequested event) {
        try {
            domainDnsPushService.push(event.domainId());
        } catch (RuntimeException e) {
            // 保持待创建状态，域名页可手动重试；订阅在同步成功前继续用服务器 IP
            log.warn("域名 {} 的 DNS 记录推送失败: {}", event.domainId(), e.getMessage());
        }
    }

    /**
     * @return domains 已绑定的 DNS 记录；legacyHosts 旧接入 host 中尚未对应到已绑定记录的部分，随节点改造迁移后消失
     */
    public Map<String, Object> getDomains(Long serverId) {
        List<DomainDnsRecord> records = domainDnsRecordRepository.findByServerId(serverId);
        Set<String> boundNames = new HashSet<>();
        List<Map<String, Object>> domains = new ArrayList<>();
        for (DomainDnsRecord record : records) {
            if (record.getFullName() != null) {
                boundNames.add(record.getFullName().toLowerCase(Locale.ROOT));
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", record.getId());
            item.put("domainId", record.getDomainId());
            item.put("fullName", record.getFullName());
            item.put("type", record.getType());
            item.put("content", record.getContent());
            item.put("ttl", record.getTtl());
            item.put("proxied", record.getProxied());
            item.put("status", record.getStatus());
            domains.add(item);
        }
        List<String> legacyHosts = serverHostRepository.findByServerId(serverId).stream()
                .map(ServerHost::getHost)
                .filter(host -> host != null && !host.isBlank())
                .filter(host -> !boundNames.contains(host.trim().toLowerCase(Locale.ROOT)))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domains", domains);
        result.put("legacyHosts", legacyHosts);
        return result;
    }
}
