package com.fun90.airopscat.service.traffic.impl;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.traffic.AbstractV2RayApiTrafficStatsCollector;
import com.fun90.airopscat.service.traffic.UserTrafficStats;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@ApplicationScoped
@SupportedCores(value = {"xray", "xray-core"}, priority = 1, description = "Xray 流量统计采集策略")
public class XrayTrafficStatsCollector extends AbstractV2RayApiTrafficStatsCollector {

    private static final String XRAY_STATS_QUERY_COMMAND = "xray api statsquery --server=127.0.0.1:100 --reset=true";

    @Override
    public Map<String, UserTrafficStats> collectUserTrafficStats(SshConnection connection, Server server, ServerConfig serverConfig) {
        try {
            CommandResult result = connection.executeCommand(XRAY_STATS_QUERY_COMMAND);
            if (!result.isSuccess()) {
                log.error("执行 xray statsquery 命令失败, serverId={}, stderr={}", server.getId(), result.getStderr());
                return Collections.emptyMap();
            }

            String output = result.getStdout();
            if (output == null || output.trim().isEmpty()) {
                log.debug("xray statsquery 返回空结果, serverId={}", server.getId());
                return Collections.emptyMap();
            }

            return parseUserTrafficStats(parseStats(output));
        } catch (Exception e) {
            log.error("获取 xray 流量统计失败, serverId={}", server.getId());
            return Collections.emptyMap();
        }
    }

    @Override
    public String getStrategyName() {
        return "xray";
    }

    @SuppressWarnings("unchecked")
    private List<NamedTrafficStat> parseStats(String output) {
        try {
            Map<String, Object> jsonMap = JsonUtil.toObject(output, Map.class);
            if (jsonMap == null) {
                return Collections.emptyList();
            }

            Object statObj = jsonMap.get("stat");
            if (!(statObj instanceof List<?> stats)) {
                return Collections.emptyList();
            }

            List<NamedTrafficStat> trafficStats = new ArrayList<>();
            for (Object statEntry : stats) {
                if (!(statEntry instanceof Map<?, ?> stat)) {
                    continue;
                }

                Object nameObj = stat.get("name");
                Object valueObj = stat.get("value");
                if (!(nameObj instanceof String name) || valueObj == null) {
                    continue;
                }

                Long value = toLong(valueObj);
                if (value != null) {
                    trafficStats.add(new NamedTrafficStat(name, value));
                }
            }
            return trafficStats;
        } catch (Exception e) {
            log.warn("解析 xray statsquery 输出失败: {}", output, e);
            return Collections.emptyList();
        }
    }

    private Long toLong(Object valueObj) {
        if (valueObj instanceof Number number) {
            return number.longValue();
        }
        if (valueObj instanceof String stringValue) {
            try {
                return Long.parseLong(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
