package com.fun90.airopscat.service;

import com.fun90.airopscat.model.convert.NodeConverter;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerTrafficStats;
import com.fun90.airopscat.model.entity.SystemLog;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.model.enums.SystemLogCategory;
import com.fun90.airopscat.repository.DomainDnsRecordRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.NodeServerRepository;
import com.fun90.airopscat.repository.ServerTrafficStatsRepository;
import com.fun90.airopscat.service.expiration.ServerTrafficThresholdNotifier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器流量达到阈值时，把节点绑定的 DNS 记录切到下一个未超阈值的部署位；周期重置后按优先级自然切回。
 */
@Slf4j
@ApplicationScoped
public class NodeFailoverService {

    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;
    private static final String REASON_THRESHOLD = "THRESHOLD";
    private static final String REASON_RESET = "RESET";
    private static final String REASON_MANUAL = "MANUAL";

    @Inject
    NodeRepository nodeRepository;

    @Inject
    NodeServerRepository nodeServerRepository;

    @Inject
    DomainDnsRecordRepository domainDnsRecordRepository;

    @Inject
    DomainDnsPushService domainDnsPushService;

    @Inject
    ServerTrafficStatsRepository serverTrafficStatsRepository;

    @Inject
    SystemLogService systemLogService;

    @Inject
    SystemConfigService systemConfigService;

    @Inject
    BarkService barkService;

    // 全部候选超阈值的告警只发一次，直到该节点重新有可用目标；重启后重置，可接受
    private final Set<Long> exhaustedAlerted = ConcurrentHashMap.newKeySet();

    enum Outcome { NONE, SWITCHED, ALL_EXCEEDED }

    /** 一次检查的过程信息，用于写系统日志；事务回滚后仍可读取 */
    private static class Attempt {
        Long nodeId;
        String nodeName;
        String message;
        // 已确定切换目标并准备推送时才有值，失败日志据此判断
        final Map<String, Object> detail = new LinkedHashMap<>();
    }

    /** 流量采集后调用，逐个节点独立事务，单个节点失败不影响其他节点 */
    public void checkAll() {
        double threshold = currentThreshold();
        for (Long nodeId : nodeRepository.findFailoverNodeIds()) {
            try {
                run(nodeId, null, threshold);
            } catch (Exception e) {
                log.warn("节点自动切换失败, nodeId={}: {}", nodeId, e.getMessage());
            }
        }
    }

    /** 手动把 DNS 切到指定部署位；自动切换开启时下一轮会按规则重新计算 */
    public String switchManually(Long nodeId, Long nodeServerId) {
        if (nodeServerId == null) {
            throw new IllegalArgumentException("请选择要切换到的部署位");
        }
        String message = run(nodeId, nodeServerId, currentThreshold());
        if (message == null) {
            throw new IllegalArgumentException("该部署位已是当前生效部署位");
        }
        return message;
    }

    private double currentThreshold() {
        return ServerTrafficThresholdNotifier.normalizeThreshold(
                systemConfigService.getDoubleValue(ServerTrafficThresholdNotifier.TRAFFIC_THRESHOLD_KEY, 0.9));
    }

    /** @return 发生切换时的描述，否则 null */
    private String run(Long nodeId, Long forcedNodeServerId, double threshold) {
        Attempt attempt = new Attempt();
        attempt.nodeId = nodeId;
        Outcome outcome;
        try {
            outcome = QuarkusTransaction.requiringNew().call(() -> evaluate(nodeId, forcedNodeServerId, threshold, attempt));
        } catch (RuntimeException e) {
            // 推送失败时整个事务回滚，active 不变；失败日志单独提交，下一轮重试
            if (attempt.detail.containsKey("toNodeServerId")) {
                attempt.detail.put("success", false);
                systemLogService.recordIndependently(SystemLogCategory.NODE_FAILOVER, SystemLog.LEVEL_ERROR,
                        nodeId, attempt.nodeName, attempt.message + "；失败：" + e.getMessage(), attempt.detail);
            }
            throw e;
        }

        if (outcome != Outcome.ALL_EXCEEDED) {
            exhaustedAlerted.remove(nodeId);
        }
        if (outcome == Outcome.SWITCHED) {
            barkService.sendWarningNotification("AirOpsCat 节点自动切换", attempt.message);
            return attempt.message;
        }
        if (outcome == Outcome.ALL_EXCEEDED && exhaustedAlerted.add(nodeId)) {
            systemLogService.recordIndependently(SystemLogCategory.NODE_FAILOVER, SystemLog.LEVEL_WARN,
                    nodeId, attempt.nodeName, attempt.message, null);
            barkService.sendWarningNotification("AirOpsCat 节点自动切换", attempt.message);
        }
        return null;
    }

    private Outcome evaluate(Long nodeId, Long forcedNodeServerId, double threshold, Attempt attempt) {
        Node node = nodeRepository.findById(nodeId);
        if (node == null) {
            throw new EntityNotFoundException("节点不存在");
        }
        attempt.nodeName = node.getName();
        if (node.getDnsRecordId() == null) {
            throw new IllegalArgumentException("节点未绑定订阅 DNS 记录");
        }
        List<NodeServer> candidates = nodeServerRepository.findByNodeId(nodeId).stream()
                .filter(NodeServer::enabledFlag)
                .filter(placement -> NodeDeploymentStatus.isDeployed(placement.getDeployed()))
                .filter(NodeServer::exposedFlag)
                .sorted(NodeServerRepository.PRIORITY_ORDER)
                .toList();
        if (candidates.isEmpty()) {
            return Outcome.NONE;
        }

        Map<Long, Double> usage = loadUsage(candidates);
        NodeServer current = candidates.stream()
                .filter(placement -> Objects.equals(placement.getId(), node.getActiveNodeServerId()))
                .findFirst().orElse(null);
        NodeServer target;
        if (forcedNodeServerId != null) {
            target = candidates.stream()
                    .filter(placement -> placement.getId().equals(forcedNodeServerId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("目标部署位不可用：需启用、已部署且参与切换"));
        } else {
            target = selectTarget(candidates, usage, threshold);
            if (target == null) {
                attempt.message = ("节点 " + node.getName() + " 的所有部署服务器流量均已超过阈值，保持当前解析");
                return Outcome.ALL_EXCEEDED;
            }
        }
        if (current != null && current.getId().equals(target.getId())) {
            return Outcome.NONE;
        }

        DomainDnsRecord record = domainDnsRecordRepository.findById(node.getDnsRecordId());
        if (record == null) {
            throw new EntityNotFoundException("订阅 DNS 记录不存在");
        }
        String content = resolveDnsContent(record, target);
        // 尚未记录生效部署位，且解析本就指向目标时只补记，不推送
        if (current == null && content.equalsIgnoreCase(record.getContent())) {
            node.setActiveNodeServerId(target.getId());
            return Outcome.NONE;
        }

        double fromUsage = current == null ? 0 : usage.getOrDefault(current.getServerId(), 0.0);
        double toUsage = usage.getOrDefault(target.getServerId(), 0.0);
        String reason = forcedNodeServerId != null ? REASON_MANUAL
                : fromUsage >= threshold ? REASON_THRESHOLD : REASON_RESET;
        attempt.detail.put("reason", reason);
        attempt.detail.put("fromNodeServerId", current == null ? null : current.getId());
        attempt.detail.put("toNodeServerId", target.getId());
        attempt.detail.put("fromUsage", fromUsage);
        attempt.detail.put("toUsage", toUsage);
        attempt.detail.put("dnsContent", content);
        attempt.message = "节点 " + node.getName() + "：" + describe(current, fromUsage) + " → " + describe(target, toUsage)
                + "（" + reason + "），解析 " + content;

        domainDnsPushService.pushRecordContent(record.getDomainId(), record.getId(), content);
        node.setActiveNodeServerId(target.getId());
        attempt.detail.put("success", true);
        systemLogService.record(SystemLogCategory.NODE_FAILOVER, SystemLog.LEVEL_INFO,
                nodeId, node.getName(), attempt.message, attempt.detail);
        return Outcome.SWITCHED;
    }

    /** 候选已按优先级升序；取第一个使用率低于阈值的部署位，全部超阈值返回 null */
    static NodeServer selectTarget(List<NodeServer> candidates, Map<Long, Double> usageByServerId, double threshold) {
        return candidates.stream()
                .filter(placement -> usageByServerId.getOrDefault(placement.getServerId(), 0.0) < threshold)
                .findFirst().orElse(null);
    }

    /** 当期上下行之和 / 套餐流量；未设置套餐流量视为不限量，使用率为 0 */
    static double usageRatio(Server server, ServerTrafficStats stats) {
        if (server == null || server.getBandwidth() == null || server.getBandwidth() <= 0 || stats == null) {
            return 0;
        }
        long used = stats.getUploadBytes() + stats.getDownloadBytes();
        return used / (double) (server.getBandwidth().longValue() * BYTES_PER_GB);
    }

    private Map<Long, Double> loadUsage(List<NodeServer> candidates) {
        List<Long> serverIds = candidates.stream().map(NodeServer::getServerId).distinct().toList();
        Map<Long, ServerTrafficStats> statsByServerId = new HashMap<>();
        serverTrafficStatsRepository.findByServerIdsAndCurrentTime(serverIds, LocalDateTime.now())
                .forEach(stats -> statsByServerId.put(stats.getServerId(), stats));
        Map<Long, Double> usage = new HashMap<>();
        for (NodeServer placement : candidates) {
            usage.put(placement.getServerId(),
                    usageRatio(placement.getServer(), statsByServerId.get(placement.getServerId())));
        }
        return usage;
    }

    static String resolveDnsContent(DomainDnsRecord record, NodeServer target) {
        Server server = target.getServer();
        String type = Objects.toString(record.getType(), "").toUpperCase(Locale.ROOT);
        if ("A".equals(type)) {
            if (server == null || server.getIp() == null || server.getIp().isBlank()) {
                throw new IllegalStateException("目标服务器没有 IP，无法写入 A 记录");
            }
            return server.getIp();
        }
        String host = NodeConverter.resolveEffectiveHost(target.getAccessHost(), server);
        // resolveEffectiveHost 无域名时回退到 IP，CNAME 不能指向 IP
        if (host == null || host.isBlank() || (server != null && host.equals(server.getIp()))) {
            throw new IllegalStateException("目标部署位没有接入域名，无法作为 CNAME 目标");
        }
        return host;
    }

    private static String describe(NodeServer placement, double usage) {
        if (placement == null) {
            return "未记录";
        }
        Server server = placement.getServer();
        String name = server == null ? String.valueOf(placement.getServerId())
                : server.getName() != null && !server.getName().isBlank() ? server.getName() : server.getIp();
        return name + " " + Math.round(usage * 100) + "%";
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
