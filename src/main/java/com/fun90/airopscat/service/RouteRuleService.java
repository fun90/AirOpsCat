package com.fun90.airopscat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.RouteRuleDto;
import com.fun90.airopscat.model.dto.RouteRuleRequest;
import com.fun90.airopscat.model.dto.deployment.RouteRuleSnapshot;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.RouteRule;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.CoreType;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.model.enums.RouteRuleType;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.RouteRuleRepository;
import com.fun90.airopscat.repository.ServerRepository;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@ApplicationScoped
public class RouteRuleService {

    private final RouteRuleRepository routeRuleRepository;
    private final ServerRepository serverRepository;
    private final NodeRepository nodeRepository;
    private final ObjectMapper objectMapper;

    @Inject
    public RouteRuleService(RouteRuleRepository routeRuleRepository,
                            ServerRepository serverRepository,
                            NodeRepository nodeRepository,
                            ObjectMapper objectMapper) {
        this.routeRuleRepository = routeRuleRepository;
        this.serverRepository = serverRepository;
        this.nodeRepository = nodeRepository;
        this.objectMapper = objectMapper;
    }

    public PanacheQuery<RouteRule> getRouteRulePage(String search, String coreType, Boolean enabled) {
        StringBuilder query = new StringBuilder("select distinct rr from RouteRule rr left join rr.servers s where 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        if (search != null && !search.trim().isEmpty()) {
            query.append(" and (lower(rr.name) like :search or lower(rr.ruleType) like :search or lower(coalesce(rr.remark, '')) like :search");
            query.append(" or lower(coalesce(s.ip, '')) like :search or lower(coalesce(s.host, '')) like :search or lower(coalesce(s.name, '')) like :search");
            query.append(" or s.id in (select sh.serverId from ServerHost sh where lower(sh.host) like :search))");
            params.put("search", "%" + search.trim().toLowerCase() + "%");
        }
        if (coreType != null && !coreType.trim().isEmpty()) {
            query.append(" and rr.coreType = :coreType");
            params.put("coreType", requireSupportedCoreType(coreType));
        }
        if (enabled != null) {
            query.append(" and rr.enabled = :enabled");
            params.put("enabled", enabled ? 1 : 0);
        }

        return routeRuleRepository.find(query.toString(), Sort.by("rr.createTime").descending(), params);
    }

    public RouteRule getRouteRuleById(Long id) {
        return routeRuleRepository.findById(id);
    }

    public Map<Long, List<RouteRuleSnapshot>> getEnabledSnapshotsByServerIds(List<Long> serverIds) {
        Map<Long, List<RouteRuleSnapshot>> snapshotsByServerId = new LinkedHashMap<>();
        for (RouteRule routeRule : routeRuleRepository.findEnabledByServerIds(serverIds)) {
            RouteRuleSnapshot snapshot = toSnapshot(routeRule);
            for (Server server : routeRule.getServers()) {
                snapshotsByServerId.computeIfAbsent(server.getId(), key -> new ArrayList<>()).add(snapshot);
            }
        }
        return snapshotsByServerId;
    }

    public Map<String, Long> getRouteRuleStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("total", routeRuleRepository.count());
        stats.put("enabled", routeRuleRepository.countByEnabled(1));
        stats.put("disabled", routeRuleRepository.countByEnabled(0));
        stats.put("xray", routeRuleRepository.countByCoreType(CoreType.XRAY.getValue()));
        stats.put("singBox", routeRuleRepository.countByCoreType(CoreType.SING_BOX.getValue()));
        return stats;
    }

    public List<Map<String, String>> getRouteRuleTypeOptions() {
        List<Map<String, String>> options = new ArrayList<>();
        for (RouteRuleType type : Arrays.asList(RouteRuleType.values())) {
            Map<String, String> option = new LinkedHashMap<>();
            option.put("value", type.getValue());
            option.put("label", type.getDescription());
            options.add(option);
        }
        return options;
    }

    public List<Map<String, String>> getSupportedCoreTypeOptions() {
        List<Map<String, String>> options = new ArrayList<>();
        for (CoreType type : List.of(CoreType.XRAY, CoreType.SING_BOX)) {
            Map<String, String> option = new LinkedHashMap<>();
            option.put("value", type.getValue());
            option.put("label", type.getName());
            options.add(option);
        }
        return options;
    }

    @Transactional
    public RouteRuleDto saveRouteRule(RouteRuleRequest request) {
        RouteRule routeRule = new RouteRule();
        applyRequest(routeRule, request);
        routeRuleRepository.persist(routeRule);
        return toDto(routeRule);
    }

    @Transactional
    public RouteRuleDto updateRouteRule(Long id, RouteRuleRequest request) {
        RouteRule routeRule = routeRuleRepository.findById(id);
        if (routeRule == null) {
            throw new EntityNotFoundException("RouteRule not found");
        }
        applyRequest(routeRule, request);
        return toDto(routeRule);
    }

    @Transactional
    public void deleteRouteRule(Long id) {
        routeRuleRepository.deleteById(id);
    }

    @Transactional
    public RouteRule toggleRouteRuleStatus(Long id, boolean enabled) {
        RouteRule routeRule = routeRuleRepository.findById(id);
        if (routeRule == null) {
            return null;
        }

        if (enabled) {
            validateRouteRuleCanBeEnabled(routeRule);
        }

        routeRule.setEnabled(enabled ? 1 : 0);
        return routeRule;
    }

    public RouteRuleDto toDto(RouteRule routeRule) {
        RouteRuleDto dto = new RouteRuleDto();
        dto.setId(routeRule.getId());
        dto.setName(routeRule.getName());
        dto.setCoreType(routeRule.getCoreType());
        dto.setRuleType(routeRule.getRuleType());
        dto.setRuleValue(parseRuleValue(routeRule.getRuleValue()));
        dto.setOutboundNodeId(routeRule.getOutboundNodeId());
        dto.setEnabled(routeRule.getEnabled());
        dto.setRemark(routeRule.getRemark());
        dto.setCreateTime(routeRule.getCreateTime());
        dto.setUpdateTime(routeRule.getUpdateTime());

        List<Server> servers = new ArrayList<>(routeRule.getServers());
        dto.setServerIds(servers.stream().map(Server::getId).toList());
        dto.setServerNames(servers.stream().map(this::buildServerLabel).toList());

        Node outboundNode = nodeRepository.findById(routeRule.getOutboundNodeId());
        if (outboundNode != null) {
            dto.setOutboundNodeName(buildNodeLabel(outboundNode));
            if (outboundNode.getServerId() != null) {
                Server outboundServer = serverRepository.findById(outboundNode.getServerId());
                if (outboundServer != null) {
                    dto.setOutboundServerLabel(buildServerLabel(outboundServer));
                }
            }
        }
        return dto;
    }

    private void applyRequest(RouteRule routeRule, RouteRuleRequest request) {
        validateRequest(request);

        routeRule.setName(request.getName().trim());
        routeRule.setCoreType(requireSupportedCoreType(request.getCoreType()));
        routeRule.setRuleType(normalizeRuleType(request.getRuleType()));
        routeRule.setRuleValue(writeRuleValue(request.getRuleValue()));
        routeRule.setOutboundNodeId(request.getOutboundNodeId());
        routeRule.setEnabled(request.getEnabled() == null ? 1 : (request.getEnabled() == 0 ? 0 : 1));
        routeRule.setRemark(request.getRemark());
        routeRule.setServers(loadServers(request.getServerIds()));
    }

    private void validateRouteRuleCanBeEnabled(RouteRule routeRule) {
        Node outboundNode = nodeRepository.findById(routeRule.getOutboundNodeId());
        if (outboundNode == null) {
            throw new EntityNotFoundException("出站落地节点不存在");
        }
        if (!Objects.equals(outboundNode.getType(), NodeType.LANDING.getValue())) {
            throw new IllegalArgumentException("出站节点必须是落地节点");
        }
        if (outboundNode.getDisabled() != null && outboundNode.getDisabled() == 1) {
            throw new IllegalArgumentException("出站落地节点已禁用，无法启用路由规则");
        }
    }

    private void validateRequest(RouteRuleRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("路由规则不能为空");
        }
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }
        String requestCoreType = requireSupportedCoreType(request.getCoreType());
        RouteRuleType ruleType = RouteRuleType.fromValue(request.getRuleType());
        if (ruleType == null) {
            throw new IllegalArgumentException("规则类型不支持");
        }
        if (request.getRuleValue() == null) {
            throw new IllegalArgumentException("规则值不能为空");
        }
        if (ruleType == RouteRuleType.CUSTOM && !(request.getRuleValue() instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("自定义规则必须为 JSON 对象");
        }
        if (request.getServerIds() == null || request.getServerIds().isEmpty()) {
            throw new IllegalArgumentException("至少选择一个服务器");
        }
        if (request.getOutboundNodeId() == null) {
            throw new IllegalArgumentException("请选择出站落地节点");
        }

        Node outboundNode = nodeRepository.findById(request.getOutboundNodeId());
        if (outboundNode == null) {
            throw new EntityNotFoundException("出站落地节点不存在");
        }
        if (!Objects.equals(outboundNode.getType(), NodeType.LANDING.getValue())) {
            throw new IllegalArgumentException("出站节点必须是落地节点");
        }
        if (outboundNode.getDisabled() != null && outboundNode.getDisabled() == 1) {
            throw new IllegalArgumentException("出站落地节点已禁用");
        }
        if (!requestCoreType.equals(outboundNode.getCoreType())) {
            throw new IllegalArgumentException("路由规则内核类型必须与出站落地节点一致");
        }

        Set<Long> uniqueServerIds = new LinkedHashSet<>(request.getServerIds());
        List<Server> servers = serverRepository.findByIdIn(new ArrayList<>(uniqueServerIds));
        if (servers.size() != uniqueServerIds.size()) {
            throw new EntityNotFoundException("存在无效的服务器选择");
        }
    }

    private Set<Server> loadServers(List<Long> serverIds) {
        Set<Long> uniqueServerIds = new LinkedHashSet<>(serverIds);
        return new LinkedHashSet<>(serverRepository.findByIdIn(new ArrayList<>(uniqueServerIds)));
    }

    private RouteRuleSnapshot toSnapshot(RouteRule routeRule) {
        return new RouteRuleSnapshot(
                routeRule.getId(),
                routeRule.getName(),
                routeRule.getCoreType(),
                routeRule.getRuleType(),
                routeRule.getRuleValue(),
                routeRule.getOutboundNodeId()
        );
    }

    private Object parseRuleValue(String ruleValue) {
        try {
            return objectMapper.readValue(ruleValue, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析路由规则失败: " + e.getMessage(), e);
        }
    }

    private String writeRuleValue(Object ruleValue) {
        try {
            return objectMapper.writeValueAsString(ruleValue);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("规则值不是有效的 JSON: " + e.getMessage(), e);
        }
    }

    private String requireSupportedCoreType(String coreType) {
        CoreType normalized = CoreType.fromValue(coreType);
        if (normalized == null || normalized == CoreType.HYSTERIA2) {
            throw new IllegalArgumentException("仅支持 xray 和 sing-box 内核");
        }
        return normalized.getValue();
    }

    private String normalizeRuleType(String ruleType) {
        RouteRuleType normalized = RouteRuleType.fromValue(ruleType);
        return normalized == null ? null : normalized.getValue();
    }

    private String buildServerLabel(Server server) {
        String name = server.getName();
        if (name == null || name.trim().isEmpty()) {
            name = server.getHost();
        }
        if (name == null || name.trim().isEmpty()) {
            name = server.getIp();
        }
        return name + " (" + server.getIp() + ")";
    }

    private String buildNodeLabel(Node node) {
        String name = node.getName();
        if (name == null || name.trim().isEmpty()) {
            name = "节点#" + node.getId();
        }
        return name + " [" + Objects.toString(node.getProtocol(), "-") + "]";
    }
}
