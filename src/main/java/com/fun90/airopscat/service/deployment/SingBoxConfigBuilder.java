package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.dto.deployment.NodeDeploymentSnapshot;
import com.fun90.airopscat.model.dto.deployment.RouteRuleSnapshot;
import com.fun90.airopscat.model.dto.deployment.ServerSnapshot;
import com.fun90.airopscat.model.dto.deployment.NodeClient;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.enums.RouteRuleType;
import com.fun90.airopscat.service.deployment.strategy.CoreConfigBuilder;
import com.fun90.airopscat.util.ConfigFileReader;
import com.fun90.airopscat.util.JsonUtil;
import com.fun90.airopscat.util.TemplateUtil;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
@SupportedCores(value = {"sing-box"}, priority = 1, description = "Sing-box 部署配置构建策略")
public class SingBoxConfigBuilder implements CoreConfigBuilder {

    private static final List<String> DEFAULT_OUTBOUND_TAGS = List.of(
            "default-direct",
            "default-blocked"
    );

    private final ConfigFileReader configFileReader;
    private final TemplateUtil templateUtil;

    @Override
    public String build(DeploymentServerContext ctx, List<Node> nodes) {
        List<NodeDeploymentSnapshot> snapshots = nodes.stream()
                .map(Node::getId)
                .map(ctx.nodeSnapshotMap()::get)
                .filter(Objects::nonNull)
                .toList();
        return build(ctx.serverSnapshot(), snapshots, ctx.nodeSnapshotMap());
    }

    public String build(ServerSnapshot serverSnapshot, List<NodeDeploymentSnapshot> nodes) {
        return build(serverSnapshot, nodes, Collections.emptyMap());
    }

    public String build(ServerSnapshot serverSnapshot,
                        List<NodeDeploymentSnapshot> nodes,
                        Map<Long, NodeDeploymentSnapshot> nodeSnapshotMap) {
        List<NodeDeploymentSnapshot> enabledNodes = nodes.stream()
                .filter(node -> node.disabled() == 0)
                .toList();

        List<Map<String, Object>> inbounds = new ArrayList<>();
        List<Map<String, Object>> outbounds = new ArrayList<>();
        List<Map<String, Object>> routeRules = new ArrayList<>();
        List<Map<String, Object>> ruleSets = new ArrayList<>();
        List<String> statsUsers = new ArrayList<>();

        for (NodeDeploymentSnapshot node : enabledNodes) {
            applyNodeConfig(node, inbounds, outbounds, routeRules, statsUsers);
        }

        applyManagedRouteRules(serverSnapshot, nodeSnapshotMap, outbounds, routeRules, ruleSets);
        return renderConfig(inbounds, outbounds, routeRules, ruleSets, statsUsers.stream().distinct().toList());
    }

    @Override
    public String getStrategyName() {
        return "sing-box";
    }

    private void applyNodeConfig(NodeDeploymentSnapshot node,
                                 List<Map<String, Object>> inbounds,
                                 List<Map<String, Object>> outbounds,
                                 List<Map<String, Object>> routeRules,
                                 List<String> statsUsers) {
        if (node.inbound() == null) {
            log.warn("Node {} inbound config is null, skip", node.id());
            return;
        }

        Map<String, Object> inboundMap = toMap(node.inbound());
        inbounds.add(buildInbound(node, inboundMap));

        String protocol = normalize(node.protocol());
        if ("shadowsocks".equals(protocol) || "socks".equals(protocol)) {
            statsUsers.addAll(extractUsersFromInboundMap(inboundMap));
        } else {
            node.clients().stream()
                    .map(NodeClient::email)
                    .filter(Objects::nonNull)
                    .filter(name -> !name.isBlank())
                    .forEach(statsUsers::add);
        }

        if (node.outId() != null) {
            addOutboundIfAbsent(node, outbounds);
        }
        if (node.outTag() != null) {
            routeRules.add(buildRouteRule(node));
        }
    }

    private Map<String, Object> buildInbound(NodeDeploymentSnapshot node, Map<String, Object> inboundMap) {
        removeRealityPublicKey(inboundMap);
        inboundMap.put("tag", node.tag());
        inboundMap.put("listen", "::");
        inboundMap.put("listen_port", node.port());

        String protocol = normalize(node.protocol());
        if ("vless".equals(protocol) || "vless-reality".equals(protocol)) {
            inboundMap.put("users", buildVlessUsers(node.clients()));
        } else if ("hysteria2".equals(protocol)) {
            inboundMap.put("users", buildHysteria2Users(node.clients()));
        }
        return inboundMap;
    }

    private void removeRealityPublicKey(Map<String, Object> inbound) {
        Map<String, Object> tls = asMap(inbound.get("tls"));
        if (tls == null) {
            return;
        }

        Map<String, Object> reality = asMap(tls.get("reality"));
        if (reality == null) {
            return;
        }

        reality.remove("public_key");
    }

    private List<Map<String, Object>> buildVlessUsers(List<NodeClient> clients) {
        return clients.stream()
                .map(client -> {
                    Map<String, Object> user = new LinkedHashMap<>();
                    user.put("uuid", client.id());
                    user.put("name", client.email());
                    if (client.flow() != null && !client.flow().isBlank()) {
                        user.put("flow", client.flow());
                    }
                    return user;
                })
                .toList();
    }

    private List<Map<String, Object>> buildHysteria2Users(List<NodeClient> clients) {
        return clients.stream()
                .map(client -> {
                    Map<String, Object> user = new LinkedHashMap<>();
                    user.put("name", client.email());
                    user.put("password", client.id());
                    return user;
                })
                .toList();
    }

    private void addOutboundIfAbsent(NodeDeploymentSnapshot node, List<Map<String, Object>> outbounds) {
        if (node.outInbound() == null || node.outTag() == null
                || node.outServerIp() == null || node.outPort() == null) {
            return;
        }

        boolean alreadyPresent = DEFAULT_OUTBOUND_TAGS.contains(node.outTag()) || outbounds.stream()
                .map(outbound -> Objects.toString(outbound.get("tag"), null))
                .anyMatch(node.outTag()::equals);
        if (alreadyPresent) {
            return;
        }

        Map<String, Object> outbound = buildOutbound(node);
        if (outbound == null) {
            return;
        }
        outbound.put("tag", node.outTag());
        outbounds.add(outbound);
    }

    private Map<String, Object> buildOutbound(NodeDeploymentSnapshot node) {
        return buildOutboundFromInbound(node.outInbound(), node.outProtocol(), node.outServerIp(), node.outPort());
    }

    private Map<String, Object> buildOutboundFromInbound(String inboundJson,
                                                         String protocolValue,
                                                         String serverAddress,
                                                         Integer serverPort) {
        Map<String, Object> inbound = toMap(inboundJson);
        String protocol = normalize(protocolValue);
        return switch (protocol) {
            case "shadowsocks" -> buildShadowsocksOutbound(inbound, serverAddress, serverPort);
            case "socks" -> buildSocksOutbound(inbound, serverAddress, serverPort);
            case "vless", "vless-reality" -> buildVlessOutbound(inbound, serverAddress, serverPort);
            case "hysteria2" -> buildHysteria2Outbound(inbound, serverAddress, serverPort);
            default -> {
                log.warn("Unsupported sing-box outbound conversion protocol: {}", protocol);
                yield null;
            }
        };
    }

    private Map<String, Object> buildShadowsocksOutbound(Map<String, Object> inbound,
                                                         String serverAddress,
                                                         Integer serverPort) {
        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("type", "shadowsocks");
        outbound.put("server", serverAddress);
        outbound.put("server_port", serverPort);
        outbound.put("method", inbound.get("method"));
        outbound.put("password", inbound.get("password"));
        return outbound;
    }

    private Map<String, Object> buildSocksOutbound(Map<String, Object> inbound,
                                                   String serverAddress,
                                                   Integer serverPort) {
        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("type", "socks");
        outbound.put("server", serverAddress);
        outbound.put("server_port", serverPort);

        List<Map<String, Object>> users = asMapList(inbound.get("users"));
        if (!users.isEmpty()) {
            Map<String, Object> user = users.getFirst();
            outbound.put("username", user.get("username"));
            outbound.put("password", user.get("password"));
        }
        return outbound;
    }

    private Map<String, Object> buildVlessOutbound(Map<String, Object> inbound,
                                                   String serverAddress,
                                                   Integer serverPort) {
        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("type", "vless");
        outbound.put("server", serverAddress);
        outbound.put("server_port", serverPort);

        List<Map<String, Object>> users = asMapList(inbound.get("users"));
        if (!users.isEmpty()) {
            Map<String, Object> user = users.getFirst();
            outbound.put("uuid", user.get("uuid"));
            if (user.get("flow") != null) {
                outbound.put("flow", user.get("flow"));
            }
        }

        copyIfPresent(inbound, outbound, "tls");
        copyIfPresent(inbound, outbound, "transport");
        return outbound;
    }

    private Map<String, Object> buildHysteria2Outbound(Map<String, Object> inbound,
                                                       String serverAddress,
                                                       Integer serverPort) {
        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("type", "hysteria2");
        outbound.put("server", serverAddress);
        outbound.put("server_port", serverPort);

        List<Map<String, Object>> users = asMapList(inbound.get("users"));
        if (!users.isEmpty()) {
            outbound.put("password", users.getFirst().get("password"));
        }

        copyIfPresent(inbound, outbound, "tls");
        copyIfPresent(inbound, outbound, "up_mbps");
        copyIfPresent(inbound, outbound, "down_mbps");
        return outbound;
    }

    private Map<String, Object> buildRouteRule(NodeDeploymentSnapshot node) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("inbound", List.of(node.tag()));
        rule.put("outbound", node.outTag());
        return rule;
    }

    private String renderConfig(List<Map<String, Object>> inbounds,
                                List<Map<String, Object>> outbounds,
                                List<Map<String, Object>> routeRules,
                                List<Map<String, Object>> ruleSets,
                                List<String> statsUsers) {
        String configTemplate = configFileReader.readFileContent("config/core/sing-box.json");
        Map<String, Object> templateData = Map.of(
                "hasExtraInbounds", !inbounds.isEmpty(),
                "extraInbounds", toJsonFragments(inbounds),
                "hasExtraOutbounds", !outbounds.isEmpty(),
                "extraOutbounds", toJsonFragments(outbounds),
                "hasExtraRouteRules", !routeRules.isEmpty(),
                "extraRouteRules", toJsonFragments(routeRules),
                "hasExtraRuleSets", !ruleSets.isEmpty(),
                "extraRuleSets", toJsonFragments(ruleSets),
                "hasStatsUsers", !statsUsers.isEmpty(),
                "statsUsers", JsonUtil.toJsonString(statsUsers)
        );
        return templateUtil.processStringTemplate(configTemplate, templateData);
    }

    private void applyManagedRouteRules(ServerSnapshot serverSnapshot,
                                        Map<Long, NodeDeploymentSnapshot> nodeSnapshotMap,
                                        List<Map<String, Object>> outbounds,
                                        List<Map<String, Object>> routeRules,
                                        List<Map<String, Object>> ruleSets) {
        if (serverSnapshot.routeRules() == null || serverSnapshot.routeRules().isEmpty()) {
            return;
        }

        for (RouteRuleSnapshot routeRule : serverSnapshot.routeRules()) {
            if (!"sing-box".equalsIgnoreCase(routeRule.coreType())) {
                continue;
            }

            NodeDeploymentSnapshot outboundNode = nodeSnapshotMap.get(routeRule.outboundNodeId());
            if (outboundNode == null || outboundNode.inbound() == null
                    || outboundNode.serverIp() == null || outboundNode.port() == null) {
                log.warn("Skip sing-box route rule {}, outbound node {} is unavailable",
                        routeRule.id(), routeRule.outboundNodeId());
                continue;
            }
            if (!"sing-box".equalsIgnoreCase(Objects.toString(outboundNode.coreType(), "xray"))) {
                log.warn("Skip sing-box route rule {}, outbound node {} core type mismatch: {}",
                        routeRule.id(), routeRule.outboundNodeId(), outboundNode.coreType());
                continue;
            }

            addManagedOutboundIfAbsent(outboundNode, outbounds);
            routeRules.addAll(buildManagedRouteRules(routeRule, outboundNode.tag(), ruleSets));
        }
    }

    private void addManagedOutboundIfAbsent(NodeDeploymentSnapshot outboundNode,
                                            List<Map<String, Object>> outbounds) {
        boolean alreadyPresent = DEFAULT_OUTBOUND_TAGS.contains(outboundNode.tag()) || outbounds.stream()
                .map(outbound -> Objects.toString(outbound.get("tag"), null))
                .anyMatch(outboundNode.tag()::equals);
        if (alreadyPresent) {
            return;
        }

        Map<String, Object> outbound = buildOutboundFromInbound(outboundNode.inbound(),
                outboundNode.protocol(), outboundNode.serverIp(), outboundNode.port());
        if (outbound == null) {
            return;
        }
        outbound.put("tag", outboundNode.tag());
        outbounds.add(outbound);
    }

    private List<Map<String, Object>> buildManagedRouteRules(RouteRuleSnapshot routeRule,
                                                             String outboundTag,
                                                             List<Map<String, Object>> ruleSets) {
        RouteRuleType ruleType = RouteRuleType.fromValue(routeRule.ruleType());
        Object ruleValue = JsonUtil.toObject(routeRule.ruleValue(), Object.class);
        if (ruleType == RouteRuleType.CUSTOM) {
            Map<String, Object> customRule = new LinkedHashMap<>();
            Map<String, Object> source = asMap(ruleValue);
            if (source != null) {
                customRule.putAll(source);
            }
            customRule.put("outbound", outboundTag);
            return List.of(customRule);
        }

        if (ruleType == RouteRuleType.DOMAIN || ruleType == RouteRuleType.IP || ruleType == RouteRuleType.SOURCE_IP) {
            return buildRuleSetAwareRules(ruleType, ruleValue, outboundTag, ruleSets);
        }

        Map<String, Object> rule = new LinkedHashMap<>();
        if (ruleType != null && ruleType.getSingBoxField() != null) {
            rule.put(ruleType.getSingBoxField(), ruleValue);
        }
        rule.put("outbound", outboundTag);
        return List.of(rule);
    }

    private List<Map<String, Object>> buildRuleSetAwareRules(RouteRuleType ruleType,
                                                             Object ruleValue,
                                                             String outboundTag,
                                                             List<Map<String, Object>> ruleSets) {
        List<Object> values = toList(ruleValue);
        List<Object> plainValues = new ArrayList<>();
        List<String> remoteRuleSetTags = new ArrayList<>();

        for (Object value : values) {
            String normalized = Objects.toString(value, "").trim();
            if (normalized.isEmpty()) {
                continue;
            }

            if (normalized.startsWith("geosite:")) {
                String name = normalized.substring("geosite:".length()).trim().toLowerCase();
                if (!name.isEmpty()) {
                    String tag = "geosite-" + name;
                    remoteRuleSetTags.add(tag);
                    addRuleSetIfAbsent(ruleSets, buildRemoteRuleSet(tag,
                            "https://raw.githubusercontent.com/SagerNet/sing-geosite/refs/heads/rule-set/" + tag + ".srs"));
                }
                continue;
            }

            if (normalized.startsWith("geoip:")) {
                String name = normalized.substring("geoip:".length()).trim().toLowerCase();
                if (!name.isEmpty()) {
                    String tag = "geoip-" + name;
                    remoteRuleSetTags.add(tag);
                    addRuleSetIfAbsent(ruleSets, buildRemoteRuleSet(tag,
                            "https://raw.githubusercontent.com/SagerNet/sing-geoip/refs/heads/rule-set/" + tag + ".srs"));
                }
                continue;
            }

            plainValues.add(value);
        }

        List<Map<String, Object>> rules = new ArrayList<>();
        if (!plainValues.isEmpty()) {
            Map<String, Object> plainRule = new LinkedHashMap<>();
            plainRule.put(ruleType.getSingBoxField(), plainValues.size() == 1 ? plainValues.getFirst() : plainValues);
            plainRule.put("outbound", outboundTag);
            rules.add(plainRule);
        }
        if (!remoteRuleSetTags.isEmpty()) {
            Map<String, Object> ruleSetRule = new LinkedHashMap<>();
            ruleSetRule.put("rule_set", remoteRuleSetTags);
            ruleSetRule.put("outbound", outboundTag);
            rules.add(ruleSetRule);
        }
        return rules;
    }

    private void addRuleSetIfAbsent(List<Map<String, Object>> ruleSets, Map<String, Object> candidate) {
        String tag = Objects.toString(candidate.get("tag"), null);
        if ("geoip-cn".equals(tag) || "geosite-cn".equals(tag)) {
            return;
        }
        boolean exists = ruleSets.stream()
                .map(ruleSet -> Objects.toString(ruleSet.get("tag"), null))
                .anyMatch(tag::equals);
        if (!exists) {
            ruleSets.add(candidate);
        }
    }

    private Map<String, Object> buildRemoteRuleSet(String tag, String url) {
        Map<String, Object> ruleSet = new LinkedHashMap<>();
        ruleSet.put("tag", tag);
        ruleSet.put("type", "remote");
        ruleSet.put("format", "binary");
        ruleSet.put("url", url);
        ruleSet.put("download_detour", "default-direct");
        return ruleSet;
    }

    private String toJsonFragments(List<Map<String, Object>> items) {
        return items.stream()
                .map(JsonUtil::toJsonString)
                .collect(Collectors.joining(",\n"));
    }

    private List<String> extractUsersFromInboundMap(Map<String, Object> inbound) {
        List<Map<String, Object>> users = asMapList(inbound.get("users"));
        return users.stream()
                .map(user -> {
                    Object name = user.get("name");
                    if (name != null) {
                        return Objects.toString(name, "");
                    }
                    Object username = user.get("username");
                    return username != null ? Objects.toString(username, "") : "";
                })
                .filter(name -> !name.isBlank())
                .toList();
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String field) {
        Object value = source.get(field);
        if (value != null) {
            target.put(field, value);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private List<Object> toList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (value == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(List.of(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(String json) {
        return JsonUtil.toObject(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptyList();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .collect(Collectors.toList());
    }
}
