package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.NodeOnlineAccountDailyPointDto;
import com.fun90.airopscat.model.dto.NodeOnlineAccountOverviewChartDto;
import com.fun90.airopscat.model.dto.NodeOnlineAccountOverviewDailyPointDto;
import com.fun90.airopscat.model.dto.NodeOnlineAccountOverviewNodeRankDto;
import com.fun90.airopscat.model.dto.NodeOnlineAccountOverviewSummaryDto;
import com.fun90.airopscat.model.dto.NodeOnlineAccountStatsChartDto;
import com.fun90.airopscat.model.dto.NodeOnlineAccountStatsSummaryDto;
import com.fun90.airopscat.model.entity.NodeOnlineAccountDailyStats;
import com.fun90.airopscat.repository.AccountOnlineIpRepository;
import com.fun90.airopscat.repository.NodeOnlineAccountDailyStatsRepository;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class NodeOnlineAccountStatsService {

    private static final int DEFAULT_DAYS = 30;

    @Inject
    AccountOnlineIpRepository accountOnlineIpRepository;

    @Inject
    NodeOnlineAccountDailyStatsRepository dailyStatsRepository;

    @Inject
    EntityManager entityManager;

    @Inject
    SystemConfigService systemConfigService;

    @Transactional
    public int sampleToday() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate statDate = now.toLocalDate();
        LocalDateTime checkStartTime = now.minusMinutes(getOnlineCheckMinutes());
        Map<Long, List<String>> accountNosByNode = accountOnlineIpRepository.findDistinctAccountNosByNodeAfter(checkStartTime);
        accountNosByNode.forEach((nodeId, accountNos) -> updateDailyStats(nodeId, statDate, now, accountNos));
        return accountNosByNode.size();
    }

    @Transactional
    public void sampleNodeToday(Long nodeId) {
        if (nodeId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime checkStartTime = now.minusMinutes(getOnlineCheckMinutes());
        List<String> accountNos = accountOnlineIpRepository.findDistinctAccountNosByNodeAfter(checkStartTime)
                .getOrDefault(nodeId, List.of());
        updateDailyStats(nodeId, now.toLocalDate(), now, accountNos);
    }

    @Transactional
    public NodeOnlineAccountStatsSummaryDto getSummary(Long nodeId, int days) {
        NodeStatsTarget node = findNodeStatsTarget(nodeId);
        if (node == null) {
            return null;
        }
        int safeDays = clampDays(days);
        List<NodeOnlineAccountDailyStats> statsList = getStatsInRange(node.id(), safeDays);
        NodeOnlineAccountStatsSummaryDto dto = buildNodeSummary(node, safeDays);
        dto.setMonitorIntervalSeconds(Math.max(1L,
                systemConfigService.getLongValue("airopscat.node.online-account.stats.sample-minutes", 5L)) * 60L);
        dto.setDataAvailable(!statsList.isEmpty());

        NodeOnlineAccountDailyStats todayStats = statsList.stream()
                .filter(stats -> Objects.equals(stats.getStatDate(), LocalDate.now()))
                .findFirst()
                .orElse(null);
        dto.setTodayLatestOnlineAccountCount(todayStats == null ? 0 : defaultInt(todayStats.getLatestOnlineAccountCount()));
        dto.setTodayPeakOnlineAccountCount(todayStats == null ? 0 : defaultInt(todayStats.getPeakOnlineAccountCount()));
        dto.setPeriodPeakOnlineAccountCount(statsList.stream()
                .map(NodeOnlineAccountDailyStats::getPeakOnlineAccountCount)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0));
        dto.setPeriodAverageUniqueOnlineAccountCount(statsList.stream()
                .map(NodeOnlineAccountDailyStats::getUniqueOnlineAccountCount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0D));
        dto.setLastSampleTime(statsList.stream()
                .map(NodeOnlineAccountDailyStats::getLastSampleTime)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null));
        return dto;
    }

    @Transactional
    public NodeOnlineAccountStatsChartDto getChartData(Long nodeId, int days) {
        NodeStatsTarget node = findNodeStatsTarget(nodeId);
        if (node == null) {
            return null;
        }
        int safeDays = clampDays(days);
        NodeOnlineAccountStatsChartDto dto = buildNodeChart(node, safeDays);
        dto.setPoints(getStatsInRange(node.id(), safeDays).stream()
                .map(this::toPointDto)
                .toList());
        return dto;
    }

    @Transactional
    public NodeOnlineAccountOverviewSummaryDto getOverviewSummary(int days) {
        int safeDays = clampDays(days);
        List<NodeOnlineAccountDailyStats> statsList = getAllStatsInRange(safeDays);
        Map<Long, NodeStatsTarget> nodesById = findAllNodeStatsTargets();

        NodeOnlineAccountOverviewSummaryDto dto = new NodeOnlineAccountOverviewSummaryDto();
        dto.setDays(safeDays);
        dto.setTotalNodeCount((long) nodesById.size());
        dto.setNodesWithDataCount(statsList.stream()
                .map(NodeOnlineAccountDailyStats::getNodeId)
                .filter(Objects::nonNull)
                .distinct()
                .count());
        dto.setMonitorIntervalSeconds(Math.max(1L,
                systemConfigService.getLongValue("airopscat.node.online-account.stats.sample-minutes", 5L)) * 60L);
        dto.setDataAvailable(!statsList.isEmpty());

        List<NodeOnlineAccountOverviewDailyPointDto> points = buildOverviewPoints(statsList);
        LocalDate today = LocalDate.now();
        NodeOnlineAccountOverviewDailyPointDto todayPoint = points.stream()
                .filter(point -> Objects.equals(point.getStatDate(), today))
                .findFirst()
                .orElse(null);
        dto.setTodayLatestOnlineAccountCount(todayPoint == null ? 0 : defaultInt(todayPoint.getTotalLatestOnlineAccountCount()));
        dto.setTodayPeakOnlineAccountCount(todayPoint == null ? 0 : defaultInt(todayPoint.getTotalPeakOnlineAccountCount()));
        dto.setPeriodPeakOnlineAccountCount(points.stream()
                .map(NodeOnlineAccountOverviewDailyPointDto::getTotalPeakOnlineAccountCount)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0));
        dto.setPeriodAverageUniqueOnlineAccountCount(points.stream()
                .map(NodeOnlineAccountOverviewDailyPointDto::getTotalUniqueOnlineAccountCount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0D));
        dto.setLastSampleTime(statsList.stream()
                .map(NodeOnlineAccountDailyStats::getLastSampleTime)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null));
        List<NodeOnlineAccountOverviewNodeRankDto> nodeRanks = buildNodeRanks(statsList, nodesById);
        dto.setTopNodes(nodeRanks.stream()
                .sorted(Comparator.comparing(NodeOnlineAccountOverviewNodeRankDto::getPeriodPeakOnlineAccountCount,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(NodeOnlineAccountOverviewNodeRankDto::getPeriodAverageUniqueOnlineAccountCount,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(NodeOnlineAccountOverviewNodeRankDto::getNodeId))
                .limit(8)
                .toList());
        dto.setLowUsageNodes(nodeRanks.stream()
                .filter(rank -> defaultInt(rank.getPeriodPeakOnlineAccountCount()) > 0
                        || (rank.getPeriodAverageUniqueOnlineAccountCount() != null
                        && rank.getPeriodAverageUniqueOnlineAccountCount() > 0D))
                .sorted(Comparator.comparing(NodeOnlineAccountOverviewNodeRankDto::getPeriodAverageUniqueOnlineAccountCount,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(NodeOnlineAccountOverviewNodeRankDto::getPeriodPeakOnlineAccountCount,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(NodeOnlineAccountOverviewNodeRankDto::getNodeId))
                .limit(8)
                .toList());
        return dto;
    }

    @Transactional
    public NodeOnlineAccountOverviewChartDto getOverviewChartData(int days) {
        int safeDays = clampDays(days);
        NodeOnlineAccountOverviewChartDto dto = new NodeOnlineAccountOverviewChartDto();
        dto.setDays(safeDays);
        dto.setPoints(buildOverviewPoints(getAllStatsInRange(safeDays)));
        return dto;
    }

    private synchronized void updateDailyStats(Long nodeId, LocalDate statDate, LocalDateTime sampleTime, List<String> accountNos) {
        if (nodeId == null || statDate == null || sampleTime == null) {
            return;
        }
        Set<String> currentAccountNos = normalizeAccountNos(accountNos);
        NodeOnlineAccountDailyStats stats = dailyStatsRepository.findByNodeIdAndStatDate(nodeId, statDate);
        if (stats == null) {
            if (currentAccountNos.isEmpty()) {
                return;
            }
            stats = new NodeOnlineAccountDailyStats();
            stats.setNodeId(nodeId);
            stats.setStatDate(statDate);
            stats.setPeakOnlineAccountCount(0);
            stats.setUniqueOnlineAccountCount(0);
            stats.setSampleCount(0);
        }

        Set<String> seenAccountNos = parseSeenAccountNos(stats.getSeenAccountNosJson());
        seenAccountNos.addAll(currentAccountNos);
        int currentCount = currentAccountNos.size();
        stats.setLatestOnlineAccountCount(currentCount);
        stats.setPeakOnlineAccountCount(Math.max(defaultInt(stats.getPeakOnlineAccountCount()), currentCount));
        stats.setUniqueOnlineAccountCount(seenAccountNos.size());
        stats.setSampleCount(defaultInt(stats.getSampleCount()) + 1);
        stats.setLastSampleTime(sampleTime);
        stats.setSeenAccountNosJson(JsonUtil.toJsonString(new ArrayList<>(seenAccountNos)));
        if (stats.getId() == null) {
            dailyStatsRepository.persist(stats);
        }
    }

    private List<NodeOnlineAccountDailyStats> getStatsInRange(Long nodeId, int days) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1L);
        return dailyStatsRepository.findByNodeIdAndStatDateBetween(nodeId, startDate, endDate);
    }

    private List<NodeOnlineAccountDailyStats> getAllStatsInRange(int days) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1L);
        return dailyStatsRepository.findByStatDateBetween(startDate, endDate);
    }

    private List<NodeOnlineAccountOverviewDailyPointDto> buildOverviewPoints(List<NodeOnlineAccountDailyStats> statsList) {
        Map<LocalDate, List<NodeOnlineAccountDailyStats>> statsByDate = statsList.stream()
                .filter(stats -> stats.getStatDate() != null)
                .collect(Collectors.groupingBy(
                        NodeOnlineAccountDailyStats::getStatDate,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return statsByDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    List<NodeOnlineAccountDailyStats> dayStats = entry.getValue();
                    NodeOnlineAccountOverviewDailyPointDto dto = new NodeOnlineAccountOverviewDailyPointDto();
                    dto.setStatDate(entry.getKey());
                    dto.setTotalLatestOnlineAccountCount(dayStats.stream()
                            .map(NodeOnlineAccountDailyStats::getLatestOnlineAccountCount)
                            .filter(Objects::nonNull)
                            .mapToInt(Integer::intValue)
                            .sum());
                    dto.setTotalPeakOnlineAccountCount(dayStats.stream()
                            .map(NodeOnlineAccountDailyStats::getPeakOnlineAccountCount)
                            .filter(Objects::nonNull)
                            .mapToInt(Integer::intValue)
                            .sum());
                    dto.setTotalUniqueOnlineAccountCount(dayStats.stream()
                            .map(NodeOnlineAccountDailyStats::getUniqueOnlineAccountCount)
                            .filter(Objects::nonNull)
                            .mapToInt(Integer::intValue)
                            .sum());
                    dto.setActiveNodeCount((int) dayStats.stream()
                            .map(NodeOnlineAccountDailyStats::getNodeId)
                            .filter(Objects::nonNull)
                            .distinct()
                            .count());
                    return dto;
                })
                .toList();
    }

    private List<NodeOnlineAccountOverviewNodeRankDto> buildNodeRanks(
            List<NodeOnlineAccountDailyStats> statsList,
            Map<Long, NodeStatsTarget> nodesById
    ) {
        Map<Long, List<NodeOnlineAccountDailyStats>> statsByNode = statsList.stream()
                .filter(stats -> stats.getNodeId() != null)
                .collect(Collectors.groupingBy(NodeOnlineAccountDailyStats::getNodeId));
        return statsByNode.entrySet().stream()
                .map(entry -> toNodeRankDto(entry.getKey(), entry.getValue(), nodesById.get(entry.getKey())))
                .filter(Objects::nonNull)
                .toList();
    }

    private NodeOnlineAccountOverviewNodeRankDto toNodeRankDto(
            Long nodeId,
            List<NodeOnlineAccountDailyStats> nodeStats,
            NodeStatsTarget node
    ) {
        if (nodeId == null || nodeStats == null || nodeStats.isEmpty()) {
            return null;
        }
        NodeOnlineAccountOverviewNodeRankDto dto = new NodeOnlineAccountOverviewNodeRankDto();
        if (node == null) {
            dto.setNodeId(nodeId);
            dto.setNodeDisplayName("#" + nodeId);
        } else {
            dto.setNodeId(node.id());
            dto.setNodeName(node.name());
            dto.setNodeDisplayName(formatNodeName(node));
            dto.setNodeNo(node.no());
            dto.setProtocol(node.protocol());
            dto.setServerIp(node.serverIp());
            dto.setServerHost(node.serverIp());
        }
        dto.setPeriodPeakOnlineAccountCount(nodeStats.stream()
                .map(NodeOnlineAccountDailyStats::getPeakOnlineAccountCount)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0));
        dto.setPeriodAverageUniqueOnlineAccountCount(nodeStats.stream()
                .map(NodeOnlineAccountDailyStats::getUniqueOnlineAccountCount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0D));
        LocalDate today = LocalDate.now();
        NodeOnlineAccountDailyStats todayStats = nodeStats.stream()
                .filter(stats -> Objects.equals(stats.getStatDate(), today))
                .findFirst()
                .orElse(null);
        dto.setTodayLatestOnlineAccountCount(todayStats == null ? 0 : defaultInt(todayStats.getLatestOnlineAccountCount()));
        dto.setTodayPeakOnlineAccountCount(todayStats == null ? 0 : defaultInt(todayStats.getPeakOnlineAccountCount()));
        dto.setLastSampleTime(nodeStats.stream()
                .map(NodeOnlineAccountDailyStats::getLastSampleTime)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null));
        return dto;
    }

    private NodeOnlineAccountStatsSummaryDto buildNodeSummary(NodeStatsTarget node, int days) {
        NodeOnlineAccountStatsSummaryDto dto = new NodeOnlineAccountStatsSummaryDto();
        fillNodeFields(dto, node);
        dto.setDays(days);
        return dto;
    }

    private NodeOnlineAccountStatsChartDto buildNodeChart(NodeStatsTarget node, int days) {
        NodeOnlineAccountStatsChartDto dto = new NodeOnlineAccountStatsChartDto();
        dto.setNodeId(node.id());
        dto.setNodeName(node.name());
        dto.setNodeDisplayName(formatNodeName(node));
        dto.setServerIp(node.serverIp());
        dto.setServerHost(node.serverIp());
        dto.setDays(days);
        return dto;
    }

    private void fillNodeFields(NodeOnlineAccountStatsSummaryDto dto, NodeStatsTarget node) {
        dto.setNodeId(node.id());
        dto.setNodeName(node.name());
        dto.setNodeDisplayName(formatNodeName(node));
        dto.setNodeNo(node.no());
        dto.setProtocol(node.protocol());
        dto.setServerIp(node.serverIp());
        dto.setServerHost(node.serverIp());
    }

    private NodeOnlineAccountDailyPointDto toPointDto(NodeOnlineAccountDailyStats stats) {
        NodeOnlineAccountDailyPointDto dto = new NodeOnlineAccountDailyPointDto();
        dto.setStatDate(stats.getStatDate());
        dto.setLatestOnlineAccountCount(defaultInt(stats.getLatestOnlineAccountCount()));
        dto.setPeakOnlineAccountCount(defaultInt(stats.getPeakOnlineAccountCount()));
        dto.setUniqueOnlineAccountCount(defaultInt(stats.getUniqueOnlineAccountCount()));
        dto.setSampleCount(defaultInt(stats.getSampleCount()));
        dto.setLastSampleTime(stats.getLastSampleTime());
        return dto;
    }

    private Set<String> normalizeAccountNos(List<String> accountNos) {
        Set<String> result = new HashSet<>();
        if (accountNos == null) {
            return result;
        }
        accountNos.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .forEach(result::add);
        return result;
    }

    private Set<String> parseSeenAccountNos(String json) {
        if (json == null || json.isBlank()) {
            return new HashSet<>();
        }
        try {
            String[] values = JsonUtil.toObject(json, String[].class);
            return normalizeAccountNos(values == null ? List.of() : List.of(values));
        } catch (Exception e) {
            log.warn("解析节点每日在线账户集合失败，将从空集合重新累计: {}", e.getMessage());
            return new HashSet<>();
        }
    }

    private NodeStatsTarget findNodeStatsTarget(Long nodeId) {
        if (nodeId == null) {
            return null;
        }
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                        select n.id, n.name, n.no, n.protocol, s.ip
                        from node n
                        left join server s on s.id = n.server_id
                        where n.id = ?1
                        """)
                .setParameter(1, nodeId)
                .getResultStream()
                .findFirst()
                .orElse(null);
        if (row == null) {
            return null;
        }
        return new NodeStatsTarget(
                ((Number) row[0]).longValue(),
                (String) row[1],
                row[2] == null ? null : ((Number) row[2]).intValue(),
                (String) row[3],
                (String) row[4]
        );
    }

    @SuppressWarnings("unchecked")
    private Map<Long, NodeStatsTarget> findAllNodeStatsTargets() {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select n.id, n.name, n.no, n.protocol, s.ip
                        from node n
                        left join server s on s.id = n.server_id
                        """)
                .getResultList();
        Map<Long, NodeStatsTarget> result = new HashMap<>();
        for (Object[] row : rows) {
            if (row == null || row[0] == null) {
                continue;
            }
            NodeStatsTarget node = new NodeStatsTarget(
                    ((Number) row[0]).longValue(),
                    (String) row[1],
                    row[2] == null ? null : ((Number) row[2]).intValue(),
                    (String) row[3],
                    (String) row[4]
            );
            result.put(node.id(), node);
        }
        return result;
    }

    private String formatNodeName(NodeStatsTarget node) {
        if (node.name() != null && !node.name().isBlank() && node.no() != null) {
            return node.name() + "-" + node.no();
        }
        if (node.name() != null && !node.name().isBlank()) {
            return node.name();
        }
        if (node.no() != null) {
            return String.valueOf(node.no());
        }
        return "#" + node.id();
    }

    private int clampDays(int days) {
        return Math.clamp(days <= 0 ? DEFAULT_DAYS : days, 1, 90);
    }

    private int getOnlineCheckMinutes() {
        return Math.max(1, systemConfigService.getIntValue("airopscat.online.check-minutes", 10));
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private record NodeStatsTarget(
            Long id,
            String name,
            Integer no,
            String protocol,
            String serverIp
    ) {
    }
}
