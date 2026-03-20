package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.dto.deployment.NodeDeploymentSnapshot;
import com.fun90.airopscat.model.dto.deployment.ServerSnapshot;
import com.fun90.airopscat.model.dto.deployment.VlessClient;
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
@SupportedCores(value = {"sing-box"}, priority = 1, description = "Sing-box 閮ㄧ讲閰嶇疆鏋勫缓绛栫暐")
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
        return build(ctx.serverSnapshot(), snapshots);
    }

    public String build(ServerSnapshot serverSnapshot, List<NodeDeploymentSnapshot> nodes) {
        List<NodeDeploymentSnapshot> enabledNodes = nodes.stream()
                .filter(node -> node.disabled() == 0)
                .toList();

        List<Map<String, Object>> inbounds = new ArrayList<>();
        List<Map<String, Object>> outbounds = new ArrayList<>();
        List<Map<String, Object>> routeRules = new ArrayList<>();

        for (NodeDeploymentSnapshot node : enabledNodes) {
            applyNodeConfig(node, inbounds, outbounds, routeRules);
        }

        applyServerTransitConfig(serverSnapshot, outbounds, routeRules);
        return renderConfig(inbounds, outbounds, routeRules);
    }

    @Override
    public String getStrategyName() {
        return "sing-box";
    }

    private void applyNodeConfig(NodeDeploymentSnapshot node,
                                 List<Map<String, Object>> inbounds,
                                 List<Map<String, Object>> outbounds,
                                 List<Map<String, Object>> routeRules) {
        if (node.inbound() == null) {
            log.warn("Node {} inbound config is null, skip", node.id());
            return;
        }

        inbounds.add(buildInbound(node));

        if (node.outId() != null) {
            addOutboundIfAbsent(node, outbounds);
        }
        if (node.outTag() != null) {
            routeRules.add(buildRouteRule(node));
        }
    }

    private Map<String, Object> buildInbound(NodeDeploymentSnapshot node) {
        Map<String, Object> inbound = toMap(node.inbound());
        removeRealityPublicKey(inbound);
        inbound.put("tag", node.tag());
        inbound.put("listen", "::");
        inbound.put("listen_port", node.port());

        String protocol = normalize(node.protocol());
        if ("vless".equals(protocol) || "vless-reality".equals(protocol)) {
            inbound.put("users", buildVlessUsers(node.clients()));
        } else if ("hysteria2".equals(protocol)) {
            inbound.put("users", buildHysteria2Users(node.clients()));
        }
        return inbound;
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

    private List<Map<String, Object>> buildVlessUsers(List<VlessClient> clients) {
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

    private List<Map<String, Object>> buildHysteria2Users(List<VlessClient> clients) {
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
        Map<String, Object> inbound = toMap(node.outInbound());
        String protocol = normalize(node.outProtocol());
        return switch (protocol) {
            case "shadowsocks" -> buildShadowsocksOutbound(inbound, node.outServerIp(), node.outPort());
            case "socks" -> buildSocksOutbound(inbound, node.outServerIp(), node.outPort());
            case "vless", "vless-reality" -> buildVlessOutbound(inbound, node.outServerIp(), node.outPort());
            case "hysteria2" -> buildHysteria2Outbound(inbound, node.outServerIp(), node.outPort());
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
                                List<Map<String, Object>> routeRules) {
        String configTemplate = configFileReader.readFileContent("config/core/sing-box.json");
        Map<String, Object> templateData = Map.of(
                "hasExtraInbounds", !inbounds.isEmpty(),
                "extraInbounds", toJsonFragments(inbounds),
                "hasExtraOutbounds", !outbounds.isEmpty(),
                "extraOutbounds", toJsonFragments(outbounds),
                "hasExtraRouteRules", !routeRules.isEmpty(),
                "extraRouteRules", toJsonFragments(routeRules)
        );
        return templateUtil.processStringTemplate(configTemplate, templateData);
    }

    private void applyServerTransitConfig(ServerSnapshot serverSnapshot,
                                          List<Map<String, Object>> outbounds,
                                          List<Map<String, Object>> routeRules) {
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

        Map<String, Object> route = asMap(transit.get("route"));
        if (route != null) {
            routeRules.addAll(asMapList(route.get("rules")));
        }
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String field) {
        Object value = source.get(field);
        if (value != null) {
            target.put(field, value);
        }
    }

    private String toJsonFragments(List<Map<String, Object>> items) {
        return items.stream()
                .map(JsonUtil::toJsonString)
                .collect(Collectors.joining(",\n"));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
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
