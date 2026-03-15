package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.OutboundConfig;
import com.fun90.airopscat.model.dto.xray.RoutingConfig;
import com.fun90.airopscat.model.dto.xray.XrayConfig;
import com.fun90.airopscat.model.dto.xray.routing.RoutingRule;
import com.fun90.airopscat.model.dto.xray.setting.InboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.VlessInboundSetting;
import com.fun90.airopscat.model.dto.deployment.ServerSnapshot;
import com.fun90.airopscat.model.dto.deployment.XrayNodeSnapshot;
import com.fun90.airopscat.service.xray.registry.ConversionStrategyRegistry;
import com.fun90.airopscat.service.xray.strategy.ConversionStrategy;
import com.fun90.airopscat.util.ConfigFileReader;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class XrayConfigBuilder {

    private final ConversionStrategyRegistry strategyRegistry;
    private final ConfigFileReader configFileReader;

    public XrayConfig build(ServerSnapshot serverSnapshot, List<XrayNodeSnapshot> nodes) {
        List<XrayNodeSnapshot> enabledNodes = nodes.stream()
                .filter(node -> node.disabled() == 0)
                .toList();

        XrayConfig xrayConfig = loadBaseConfig();
        List<InboundConfig> inbounds = extractDefaultInbounds(xrayConfig);
        List<OutboundConfig> outbounds = extractDefaultOutbounds(xrayConfig);
        List<RoutingRule> routingRules = extractDefaultRoutingRules(xrayConfig);

        for (XrayNodeSnapshot node : enabledNodes) {
            applyNodeConfig(node, inbounds, outbounds, routingRules);
        }

        applyServerTransitConfig(serverSnapshot, outbounds, routingRules);

        xrayConfig.setInbounds(inbounds);
        xrayConfig.setOutbounds(outbounds);
        xrayConfig.getRouting().setRules(routingRules);
        return xrayConfig;
    }

    private XrayConfig loadBaseConfig() {
        String configTemplate = configFileReader.readFileContent("config/core/xray.json");
        return JsonUtil.toObject(configTemplate, XrayConfig.class);
    }

    private List<InboundConfig> extractDefaultInbounds(XrayConfig config) {
        return config.getInbounds().stream()
                .filter(c -> c.getTag() != null && c.getTag().startsWith("default-"))
                .collect(Collectors.toList());
    }

    private List<OutboundConfig> extractDefaultOutbounds(XrayConfig config) {
        return config.getOutbounds().stream()
                .filter(c -> c.getTag() != null && c.getTag().startsWith("default-"))
                .collect(Collectors.toList());
    }

    private List<RoutingRule> extractDefaultRoutingRules(XrayConfig config) {
        return config.getRouting().getRules().stream()
                .filter(r -> r.getRuleTag() != null && r.getRuleTag().startsWith("default-"))
                .collect(Collectors.toList());
    }

    private void applyNodeConfig(XrayNodeSnapshot node, List<InboundConfig> inbounds,
                                  List<OutboundConfig> outbounds, List<RoutingRule> routingRules) {
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

    private InboundConfig buildInbound(XrayNodeSnapshot node) {
        InboundConfig inbound = JsonUtil.toObject(node.inbound(), InboundConfig.class);
        InboundSetting settings = inbound.getSettings();
        if (settings instanceof VlessInboundSetting vless) {
            vless.setClients(node.clients());
        }
        inbound.setTag(node.tag());
        inbound.setPort(node.port());
        return inbound;
    }

    private void addOutboundIfAbsent(XrayNodeSnapshot node, List<OutboundConfig> outbounds) {
        if (node.outInbound() == null || node.outTag() == null
                || node.outServerIp() == null || node.outPort() == null) {
            return;
        }
        boolean alreadyPresent = outbounds.stream()
                .anyMatch(o -> o.getTag().equals(node.outTag()));
        if (alreadyPresent) {
            return;
        }

        InboundConfig outInbound = JsonUtil.toObject(node.outInbound(), InboundConfig.class);
        ConversionStrategy strategy = strategyRegistry.getStrategy(outInbound.getProtocol());
        if (strategy == null) {
            return;
        }
        OutboundConfig outbound = strategy.convert(outInbound, node.outServerIp(), node.outPort());
        outbound.setTag(node.outTag());
        outbounds.add(outbound);
    }

    private RoutingRule buildRoutingRule(XrayNodeSnapshot node) {
        RoutingRule rule = new RoutingRule();
        rule.setInboundTag(Collections.singletonList(node.tag()));
        rule.setOutboundTag(node.outTag());
        rule.setType("field");
        return rule;
    }

    private void applyServerTransitConfig(ServerSnapshot serverSnapshot,
                                           List<OutboundConfig> outbounds,
                                           List<RoutingRule> routingRules) {
        String transitConfig = serverSnapshot.transitConfig();
        if (transitConfig == null || transitConfig.equals("{}")) {
            return;
        }

        XrayConfig transit = JsonUtil.toObject(transitConfig, XrayConfig.class);

        List<OutboundConfig> transitOutbounds = transit.getOutbounds();
        if (transitOutbounds != null && !transitOutbounds.isEmpty()) {
            List<String> existingTags = outbounds.stream().map(OutboundConfig::getTag).toList();
            transitOutbounds.stream()
                    .filter(o -> !existingTags.contains(o.getTag()))
                    .forEach(outbounds::add);
        }

        RoutingConfig routing = transit.getRouting();
        if (routing != null && routing.getRules() != null) {
            routingRules.addAll(routing.getRules());
        }
    }
}