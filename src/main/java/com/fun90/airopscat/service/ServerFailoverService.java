package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.AccountNodeSubscriptionDomainBinding;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.SystemLog;
import com.fun90.airopscat.model.enums.DnsRecordStatus;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.model.enums.SystemLogCategory;
import com.fun90.airopscat.repository.AccountNodeSubscriptionDomainBindingRepository;
import com.fun90.airopscat.repository.DomainDnsRecordRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.NodeServerRepository;
import com.fun90.airopscat.service.expiration.ServerTrafficThresholdNotifier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 服务器流量达到阈值时，把引用它的域名（部署位域名、账户订阅定制域名）切到同一节点其他部署位的服务器；
 * 周期重置后下一轮自然算回归属服务器。每轮按当前使用率重新计算，不保存切换状态。
 */
@Slf4j
@ApplicationScoped
public class ServerFailoverService {

    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;
    private static final String REASON_THRESHOLD = "THRESHOLD";
    private static final String REASON_RESET = "RESET";

    @Inject
    NodeRepository nodeRepository;

    @Inject
    NodeServerRepository nodeServerRepository;

    @Inject
    AccountNodeSubscriptionDomainBindingRepository bindingRepository;

    @Inject
    DomainDnsRecordRepository domainDnsRecordRepository;

    @Inject
    DomainDnsPushService domainDnsPushService;

    @Inject
    ServerMonitorStatsService serverMonitorStatsService;

    @Inject
    SystemLogService systemLogService;

    @Inject
    SystemConfigService systemConfigService;

    @Inject
    BarkService barkService;

    // 全部候选超阈值的告警、跳过原因的警告各只发一次，直到条件消失；重启后重置，可接受
    private final Set<Long> exhaustedAlerted = ConcurrentHashMap.newKeySet();
    private final Set<String> skipWarned = ConcurrentHashMap.newKeySet();
    // 时段计划记录的“全部候选超阈值”告警，与 plan() 的 exhaustedAlerted 分开，避免被其每轮 retainAll 清掉
    private final Set<Long> scheduleExhaustedAlerted = ConcurrentHashMap.newKeySet();
    // 采集任务重叠时后一轮直接跳过，避免对同一记录重复推送
    private final ReentrantLock running = new ReentrantLock();

    /** 域名引用：引用方所在的部署位（域名随该部署位的服务器归属） */
    private record Ref(NodeServer home) {}

    /** 一次待执行的切换 */
    record Switch(Long recordId, Long domainId, String fullName, String fromContent, String content, String homeContent,
                  String message, Map<String, Object> detail) {}

    /** 一次计算的结果：待切换的记录，以及候选全部超阈值的记录 */
    record Plan(List<Switch> switches, List<String> exhausted) {}

    /** 流量采集后调用，逐条记录独立事务，单条失败不影响其他记录 */
    public void checkAll() {
        if (!running.tryLock()) {
            log.info("上一轮服务器自动切换尚未结束，本轮跳过");
            return;
        }
        try {
            double threshold = ServerTrafficThresholdNotifier.normalizeThreshold(
                    systemConfigService.getDoubleValue(ServerTrafficThresholdNotifier.TRAFFIC_THRESHOLD_KEY, 0.9));
            Plan plan = QuarkusTransaction.requiringNew().call(() -> plan(threshold));
            plan.exhausted().forEach(message -> {
                systemLogService.recordIndependently(SystemLogCategory.SERVER_FAILOVER, SystemLog.LEVEL_WARN,
                        null, null, message, null);
                barkService.sendWarningNotification("AirOpsCat 服务器自动切换", message);
            });
            for (Switch change : plan.switches()) {
                try {
                    apply(change);
                } catch (RuntimeException e) {
                    log.warn("域名自动切换失败, recordId={}: {}", change.recordId(), e.getMessage());
                }
            }
        } finally {
            running.unlock();
        }
    }

    /**
     * 时段计划记录由时段任务唯一写入：want（本时段应解析的 IP）所属服务器流量超阈值时，换成同节点第一个未超阈值的候选。
     * 时段内、外两个 IP 必须同属唯一一个节点的已部署服务器，否则无法判断归属，原样返回 want；全部候选超阈值也返回 want 并告警一次。
     */
    public String resolveScheduled(DomainDnsRecord record, String want) {
        List<NodeServer> candidates = scheduleCandidates(record);
        if (candidates == null) {
            return want;
        }
        NodeServer home = findByIp(candidates, want);
        Node node = nodeRepository.findById(candidates.getFirst().getNodeId());
        if (home == null || node == null || !Objects.equals(node.getType(), NodeType.PROXY.getValue())
                || Objects.equals(node.getDisabled(), 1)) {
            return want;
        }
        double threshold = ServerTrafficThresholdNotifier.normalizeThreshold(
                systemConfigService.getDoubleValue(ServerTrafficThresholdNotifier.TRAFFIC_THRESHOLD_KEY, 0.9));
        Map<Long, Double> usage = loadUsage(candidates);
        NodeServer target = selectTarget(home, candidates, usage, threshold);
        if (target == null) {
            if (scheduleExhaustedAlerted.add(record.getId())) {
                String message = "域名 " + record.getFullName() + "：节点 " + node.getName()
                        + " 的所有部署服务器流量均已超过阈值，时段计划保持 " + want;
                systemLogService.recordIndependently(SystemLogCategory.SERVER_FAILOVER, SystemLog.LEVEL_WARN,
                        record.getId(), record.getFullName(), message, null);
                barkService.sendWarningNotification("AirOpsCat 服务器自动切换", message);
            }
            return want;
        }
        scheduleExhaustedAlerted.remove(record.getId());
        String content = target.getServer() == null ? null : target.getServer().getIp();
        if (content == null || content.isBlank()) {
            return want;
        }
        if (!content.equalsIgnoreCase(want) && !content.equalsIgnoreCase(record.getContent())) {
            String message = "域名 " + record.getFullName() + "（节点 " + node.getName() + "，时段计划）："
                    + want + " 超阈值 → " + describe(target, usage);
            systemLogService.record(SystemLogCategory.SERVER_FAILOVER, SystemLog.LEVEL_INFO,
                    record.getId(), record.getFullName(), message, null);
            barkService.sendWarningNotification("AirOpsCat 服务器自动切换", message);
        }
        return content;
    }

    // 时段内、外 IP 同属的唯一节点的已部署候选（按优先级升序）；不唯一或不存在返回 null
    private List<NodeServer> scheduleCandidates(DomainDnsRecord record) {
        Map<Long, List<NodeServer>> byNode = nodeServerRepository.list("deployed = ?1", NodeDeploymentStatus.DEPLOYED.getValue())
                .stream().collect(Collectors.groupingBy(NodeServer::getNodeId));
        List<List<NodeServer>> matched = byNode.values().stream()
                .filter(group -> findByIp(group, record.getScheduleContentIn()) != null
                        && findByIp(group, record.getScheduleContentOut()) != null)
                .toList();
        if (matched.size() != 1) {
            return null;
        }
        return matched.getFirst().stream().sorted(NodeServerRepository.PRIORITY_ORDER).toList();
    }

    private static NodeServer findByIp(List<NodeServer> placements, String ip) {
        return placements.stream()
                .filter(placement -> placement.getServer() != null && placement.getServer().getIp() != null
                        && placement.getServer().getIp().equalsIgnoreCase(ip))
                .findFirst().orElse(null);
    }

    private void apply(Switch change) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                // 计划生成后记录可能被人工修改、重新同步或解绑，内容与计划时不一致就放弃，下一轮重算
                DomainDnsRecord record = domainDnsRecordRepository.findById(change.recordId());
                if (record == null || !isSwitchable(record) || !change.fromContent().equalsIgnoreCase(record.getContent())) {
                    log.info("域名 {} 在计划后已变化，本次切换放弃", change.fullName());
                    return;
                }
                domainDnsPushService.pushRecordContent(change.domainId(), change.recordId(), change.content());
                // 切离归属服务器时记下归属 IP，切回后清除；之后才允许自动切回
                record.setFailoverHomeContent(change.homeContent());
                change.detail().put("success", true);
                systemLogService.record(SystemLogCategory.SERVER_FAILOVER, SystemLog.LEVEL_INFO,
                        change.recordId(), change.fullName(), change.message(), change.detail());
            });
        } catch (RuntimeException e) {
            // 推送失败时事务回滚，记录内容不变；失败日志单独提交，下一轮重试
            change.detail().put("success", false);
            systemLogService.recordIndependently(SystemLogCategory.SERVER_FAILOVER, SystemLog.LEVEL_ERROR,
                    change.recordId(), change.fullName(), change.message() + "；失败：" + e.getMessage(), change.detail());
            throw e;
        }
        barkService.sendWarningNotification("AirOpsCat 服务器自动切换", change.message());
    }

    Plan plan(double threshold) {
        Map<Long, List<Ref>> refsByRecord = collectRefs();
        List<Switch> switches = new ArrayList<>();
        List<String> exhausted = new ArrayList<>();
        Set<Long> stillExhausted = new java.util.HashSet<>();

        for (Map.Entry<Long, List<Ref>> entry : refsByRecord.entrySet()) {
            Long recordId = entry.getKey();
            DomainDnsRecord record = domainDnsRecordRepository.findById(recordId);
            if (record == null || !isSwitchable(record)) {
                continue;
            }
            NodeServer home = commonHome(recordId, record, entry.getValue());
            if (home == null) {
                continue;
            }
            Node node = nodeRepository.findById(home.getNodeId());
            if (node == null || !Objects.equals(node.getType(), NodeType.PROXY.getValue())
                    || Objects.equals(node.getDisabled(), 1)) {
                continue;
            }
            List<NodeServer> candidates = nodeServerRepository.findByNodeId(node.getId()).stream()
                    .filter(placement -> NodeDeploymentStatus.isDeployed(placement.getDeployed()))
                    .sorted(NodeServerRepository.PRIORITY_ORDER)
                    .toList();
            if (candidates.stream().noneMatch(placement -> placement.getId().equals(home.getId()))) {
                continue;
            }
            Set<String> candidateIps = candidates.stream()
                    .map(placement -> placement.getServer() == null ? null : placement.getServer().getIp())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            // 记录没有解析到该节点的任何服务器（如经中转），无法判断当前指向，不覆盖
            if (record.getContent() == null || candidateIps.stream().noneMatch(record.getContent()::equalsIgnoreCase)) {
                warnSkippedOnce(recordId, "域名 " + record.getFullName() + " 当前解析 " + record.getContent()
                        + " 不是节点 " + node.getName() + " 任一部署服务器的 IP，自动切换已跳过");
                continue;
            }
            // 没有被系统切走过、解析又不是归属服务器的记录，视为人工指向，不擅自改回归属服务器
            String homeIp = home.getServer() == null ? null : home.getServer().getIp();
            if (homeIp == null || (record.getFailoverHomeContent() == null && !homeIp.equalsIgnoreCase(record.getContent()))) {
                warnSkippedOnce(recordId, "域名 " + record.getFullName() + " 当前解析 " + record.getContent()
                        + " 不是归属服务器的 IP " + homeIp + "，视为人工指定，自动切换已跳过");
                continue;
            }

            Map<Long, Double> usage = loadUsage(candidates);
            NodeServer target = selectTarget(home, candidates, usage, threshold);
            if (target == null) {
                stillExhausted.add(recordId);
                if (exhaustedAlerted.add(recordId)) {
                    exhausted.add("域名 " + record.getFullName() + "：节点 " + node.getName()
                            + " 的所有部署服务器流量均已超过阈值，保持当前解析");
                }
                continue;
            }
            String content = target.getServer() == null ? null : target.getServer().getIp();
            if (content == null || content.isBlank()) {
                continue;
            }
            if (content.equalsIgnoreCase(record.getContent())) {
                if (target.getId().equals(home.getId())) {
                    record.setFailoverHomeContent(null);
                }
                continue;
            }

            double homeUsage = usage.getOrDefault(home.getServerId(), 0.0);
            String reason = homeUsage >= threshold ? REASON_THRESHOLD : REASON_RESET;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", reason);
            detail.put("nodeId", node.getId());
            detail.put("homeServerId", home.getServerId());
            detail.put("toServerId", target.getServerId());
            detail.put("fromContent", record.getContent());
            detail.put("toContent", content);
            detail.put("homeUsage", homeUsage);
            detail.put("toUsage", usage.getOrDefault(target.getServerId(), 0.0));
            String message = "域名 " + record.getFullName() + "（节点 " + node.getName() + "）："
                    + record.getContent() + " → " + describe(target, usage) + "（" + reason + "）";
            String homeContent = target.getId().equals(home.getId()) ? null : homeIp;
            switches.add(new Switch(recordId, record.getDomainId(), record.getFullName(), record.getContent(), content, homeContent,
                    message, detail));
        }
        exhaustedAlerted.retainAll(stillExhausted);
        return new Plan(switches, exhausted);
    }

    // 域名引用：节点部署位的域名，以及启用的账户订阅定制域名（归属其选定的部署位）
    private Map<Long, List<Ref>> collectRefs() {
        Map<Long, List<Ref>> refs = new HashMap<>();
        for (NodeServer placement : nodeServerRepository.list("accessHostId is not null")) {
            if (!NodeDeploymentStatus.isPendingDelete(placement.getDeployed())) {
                refs.computeIfAbsent(placement.getAccessHostId(), key -> new ArrayList<>()).add(new Ref(placement));
            }
        }
        for (AccountNodeSubscriptionDomainBinding binding : bindingRepository.list("enabled = 1 and domainDnsRecordId is not null")) {
            Long placementId = binding.getNodeServerIds().stream().findFirst().orElse(null);
            NodeServer placement = placementId == null ? null : nodeServerRepository.findById(placementId);
            if (placement != null && Objects.equals(placement.getNodeId(), binding.getNodeId())) {
                refs.computeIfAbsent(binding.getDomainDnsRecordId(), key -> new ArrayList<>()).add(new Ref(placement));
            }
        }
        return refs;
    }

    // 只处理已同步、无待推送、不按时段计划变更的 A 记录；content 由系统托管
    private boolean isSwitchable(DomainDnsRecord record) {
        return "A".equalsIgnoreCase(record.getType())
                && record.getStatus() == DnsRecordStatus.SYNCED
                && record.getExternalRecordId() != null && !record.getExternalRecordId().isBlank()
                && !Boolean.TRUE.equals(record.getScheduleEnabled());
    }

    // 多个引用方必须属于同一节点、同一服务器，否则切换目标不唯一，跳过
    private NodeServer commonHome(Long recordId, DomainDnsRecord record, List<Ref> refs) {
        NodeServer first = refs.getFirst().home();
        boolean consistent = refs.stream().allMatch(ref ->
                Objects.equals(ref.home().getNodeId(), first.getNodeId())
                        && Objects.equals(ref.home().getServerId(), first.getServerId()));
        if (!consistent) {
            warnSkippedOnce(recordId, "域名 " + record.getFullName() + " 被不同节点或服务器的部署位 / 订阅定制引用，自动切换已跳过");
            return null;
        }
        return first;
    }

    private void warnSkippedOnce(Long recordId, String message) {
        if (skipWarned.add(recordId + ":" + message)) {
            log.warn(message);
        }
    }

    /** 归属服务器低于阈值时仍指向它；否则取其他候选中（已按优先级升序）第一个低于阈值的，全部超阈值返回 null */
    static NodeServer selectTarget(NodeServer home, List<NodeServer> candidates,
                                   Map<Long, Double> usageByServerId, double threshold) {
        if (usageByServerId.getOrDefault(home.getServerId(), 0.0) < threshold) {
            return home;
        }
        return candidates.stream()
                .filter(placement -> !placement.getId().equals(home.getId()))
                .filter(placement -> usageByServerId.getOrDefault(placement.getServerId(), 0.0) < threshold)
                .findFirst().orElse(null);
    }

    /** vnstat 当月网卡总流量 / 套餐流量（与流量告警同一口径）；未设置套餐流量视为不限量，使用率为 0 */
    static double usageRatio(Server server, long usedBytes) {
        if (server == null || server.getBandwidth() == null || server.getBandwidth() <= 0) {
            return 0;
        }
        return usedBytes / (double) (server.getBandwidth().longValue() * BYTES_PER_GB);
    }

    private Map<Long, Double> loadUsage(List<NodeServer> candidates) {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, Double> usage = new HashMap<>();
        for (NodeServer placement : candidates) {
            Server server = placement.getServer();
            usage.put(placement.getServerId(), server == null ? 0.0
                    : usageRatio(server, serverMonitorStatsService.getCurrentPeriodTotalTrafficBytes(server, now)));
        }
        return usage;
    }

    private static String describe(NodeServer placement, Map<Long, Double> usage) {
        Server server = placement.getServer();
        String name = server == null ? String.valueOf(placement.getServerId())
                : server.getName() != null && !server.getName().isBlank() ? server.getName() : server.getIp();
        return name + " " + Math.round(usage.getOrDefault(placement.getServerId(), 0.0) * 100) + "%";
    }
}
