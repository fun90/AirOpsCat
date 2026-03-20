package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.dto.deployment.NodeDeploymentSnapshot;
import com.fun90.airopscat.model.dto.deployment.ServerSnapshot;
import com.fun90.airopscat.model.entity.Node;
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
@SupportedCores(value = {"xray"}, priority = 1, description = "Xray 閮ㄧ讲閰嶇疆鏋勫缓绛栫暐")
public class XrayConfigBuilder implements CoreConfigBuilder {

    private static final List<String> DEFAULT_OUTBOUND_TAGS = List.of(
            "default-direct",
            "default-blocked",
            "default-warp"
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
        return build(ctx.serverSnapshot(), snapshots);
    }

    public String build(ServerSnapshot serverSnapshot, List<NodeDeploymentSnapshot> nodes) {
        List<NodeDeploymentSnapshot> enabledNodes = nodes.stream()
                .filter(node -> node.disabled() == 0)
                .toList();

        List<Map<String, Object>> inbounds = new ArrayList<>();
        List<Map<String, Object>> outbounds = new ArrayList<>();
        List<Map<String, Object>> routingRules = new ArrayList<>();

        for (NodeDeploymentSnapshot node : enabledNodes) {
            applyNodeConfig(node, inbounds, outbounds, routingRules);
        }

        applyServerTransitConfig(serverSnapshot, outbounds, routingRules);
        return renderConfig(inbounds, outbounds, routingRules);
    }

    @Override
    public String getStrategyName() {
        return "xray";
    }

    private String renderConfig(List<Map<String, Object>> inbounds,
                                List<Map<String, Object>> outbounds,
                                List<Map<String, Object>> routingRules) {
        String configTemplate = configFileReader.readFileContent("config/core/xray.json");
        Map<String, Object> templateData = Map.of(
                "hasExtraInbounds", !inbounds.isEmpty(),
                "extraInbounds", toJsonFragments(inbounds),
                "hasExtraOutbounds", !outbounds.isEmpty(),
                "extraOutbounds", toJsonFragments(outbounds),
                "hasExtraRoutingRules", !routingRules.isEmpty(),
                "extraRoutingRules", toJsonFragments(routingRules)
        );
        return templateUtil.processStringTemplate(configTemplate, templateData);
    }

    private String toJsonFragments(List<Map<String, Object>> items) {
        return items.stream()
                .map(JsonUtil::toJsonString)
                .collect(Collectors.joining(",\n"));
    }

    private void applyNodeConfig(NodeDeploymentSnapshot node,
                                 List<Map<String, Object>> inbounds,
                                 List<Map<String, Object>> outbounds,
                                 List<Map<String, Object>> routingRules) {
        if (node.inbound() == null) {
            log.warn("Node {} inbound config is null, skip", node.id());
            return;
        }

        inbounds.add(buildInbound(node));

        if (node.outId() != null) {
            addOutboundIfAbsent(node, outbounds);
        }
        if (node.outTag() != null) {
            routingRules.add(buildRoutingRule(node));
        }
    }

    private Map<String, Object> buildInbound(NodeDeploymentSnapshot node) {
        Map<String, Object> inbound = toMap(node.inbound());
        Map<String, Object> settings = asMap(inbound.get("settings"));
        if ("vless".equalsIgnoreCase(Objects.toString(inbound.get("protocol"), null)) && settings != null) {
            settings.put("clients", node.clients());
        }
        inbound.put("tag", node.tag());
        inbound.put("port", node.port());
        return inbound;
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

        Map<String, Object> outInbound = toMap(node.outInbound());
        Map<String, Object> outbound = buildOutbound(outInbound, node.outServerIp(), node.outPort());
        if (outbound == null) {
            return;
        }
        outbound.put("tag", node.outTag());
        outbounds.add(outbound);
    }

    private Map<String, Object> buildOutbound(Map<String, Object> inbound,
                                              String serverAddress,
                                              Integer serverPort) {
        String protocol = Objects.toString(inbound.get("protocol"), "").toLowerCase();
        return switch (protocol) {
            case "vless" -> buildVlessOutbound(inbound, serverAddress, serverPort);
            case "socks", "socks5" -> buildSocksOutbound(inbound, serverAddress, serverPort);
            case "shadowsocks", "ss" -> buildShadowsocksOutbound(inbound, serverAddress, serverPort);
            default -> {
                log.warn("Unsupported outbound conversion protocol: {}", protocol);
                yield null;
            }
        };
    }

    private Map<String, Object> buildVlessOutbound(Map<String, Object> inbound,
                                                   String serverAddress,
                                                   Integer serverPort) {
        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("protocol", "vless");

        Map<String, Object> settings = asMap(inbound.get("settings"));
        List<Map<String, Object>> users = new ArrayList<>();
        for (Map<String, Object> client : asMapList(settings == null ? null : settings.get("clients"))) {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", client.get("id"));
            user.put("encryption", "none");
            user.put("flow", client.get("flow"));
            user.put("level", client.get("level"));
            users.add(user);
        }

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("address", serverAddress);
        server.put("port", serverPort);
        if (!users.isEmpty()) {
            server.put("users", users);
        }

        Map<String, Object> outboundSettings = new LinkedHashMap<>();
        outboundSettings.put("vnext", List.of(server));
        outbound.put("settings", outboundSettings);
        copyStreamSettings(inbound, outbound);
        return outbound;
    }

    private Map<String, Object> buildSocksOutbound(Map<String, Object> inbound,
                                                   String serverAddress,
                                                   Integer serverPort) {
        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("protocol", "socks");

        Map<String, Object> settings = asMap(inbound.get("settings"));
        List<Map<String, Object>> users = new ArrayList<>();
        for (Map<String, Object> account : asMapList(settings == null ? null : settings.get("accounts"))) {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("user", account.get("user"));
            user.put("pass", account.get("pass"));
            user.put("level", 0);
            users.add(user);
        }

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("address", serverAddress);
        server.put("port", serverPort);
        if (!users.isEmpty()) {
            server.put("users", users);
        }

        Map<String, Object> outboundSettings = new LinkedHashMap<>();
        outboundSettings.put("servers", List.of(server));
        outbound.put("settings", outboundSettings);
        copyStreamSettings(inbound, outbound);
        return outbound;
    }

    private Map<String, Object> buildShadowsocksOutbound(Map<String, Object> inbound,
                                                         String serverAddress,
                                                         Integer serverPort) {
        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("protocol", "shadowsocks");

        Map<String, Object> settings = asMap(inbound.get("settings"));
        List<Map<String, Object>> servers = new ArrayList<>();
        List<Map<String, Object>> clients = asMapList(settings == null ? null : settings.get("clients"));
        if (!clients.isEmpty()) {
            for (Map<String, Object> client : clients) {
                Map<String, Object> server = new LinkedHashMap<>();
                server.put("address", serverAddress);
                server.put("port", serverPort);
                server.put("method", client.get("method"));
                server.put("password", client.get("password"));
                server.put("email", client.get("email"));
                servers.add(server);
            }
        } else {
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("address", serverAddress);
            server.put("port", serverPort);
            if (settings != null) {
                server.put("method", settings.get("method"));
                server.put("password", settings.get("password"));
            }
            servers.add(server);
        }

        Map<String, Object> outboundSettings = new LinkedHashMap<>();
        outboundSettings.put("servers", servers);
        outbound.put("settings", outboundSettings);
        copyStreamSettings(inbound, outbound);
        return outbound;
    }

    private Map<String, Object> buildRoutingRule(NodeDeploymentSnapshot node) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("inboundTag", Collections.singletonList(node.tag()));
        rule.put("outboundTag", node.outTag());
        rule.put("type", "field");
        return rule;
    }

    private void applyServerTransitConfig(ServerSnapshot serverSnapshot,
                                          List<Map<String, Object>> outbounds,
                                          List<Map<String, Object>> routingRules) {
        String transitConfig = serverSnapshot.transitConfig();
        if (transitConfig == null || transitConfig.equals("{}")) {
            return;
        }

        Map<String, Object> transit = toMap(transitConfig);

        List<Map<String, Object>> transitOutbounds = asMapList(transit.get("outbounds"));
        if (!transitOutbounds.isEmpty()) {
            List<String> existingTags = new ArrayList<>(DEFAULT_OUTBOUND_TAGS);
            existingTags.addAll(outbounds.stream()
                    .map(outbound -> Objects.toString(outbound.get("tag"), null))
                    .filter(Objects::nonNull)
                    .toList());
            transitOutbounds.stream()
                    .filter(outbound -> !existingTags.contains(Objects.toString(outbound.get("tag"), null)))
                    .forEach(outbounds::add);
        }

        Map<String, Object> routing = asMap(transit.get("routing"));
        if (routing != null) {
            routingRules.addAll(asMapList(routing.get("rules")));
        }
    }

    private void copyStreamSettings(Map<String, Object> inbound, Map<String, Object> outbound) {
        Object streamSettings = inbound.get("streamSettings");
        if (streamSettings != null) {
            outbound.put("streamSettings", streamSettings);
        }
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
