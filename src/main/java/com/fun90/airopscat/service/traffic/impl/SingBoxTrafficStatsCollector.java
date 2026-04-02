package com.fun90.airopscat.service.traffic.impl;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.proto.v2rayapi.QueryStatsRequest;
import com.fun90.airopscat.proto.v2rayapi.QueryStatsResponse;
import com.fun90.airopscat.proto.v2rayapi.Stat;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.traffic.AbstractV2RayApiTrafficStatsCollector;
import com.fun90.airopscat.service.traffic.UserTrafficStats;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@ApplicationScoped
@SupportedCores(value = {"sing-box", "singbox"}, priority = 1, description = "Sing-box 流量统计采集策略")
public class SingBoxTrafficStatsCollector extends AbstractV2RayApiTrafficStatsCollector {

    @Inject
    SingBoxGrpcQueryClient grpcQueryClient;

    @Override
    public Map<String, UserTrafficStats> collectUserTrafficStats(SshConnection connection, Server server, ServerConfig serverConfig) {
        try {
            QueryStatsResponse response = grpcQueryClient.queryStats(connection, QueryStatsRequest.newBuilder()
                    .setReset(true)
                    .addPatterns("user>>>.*>>>traffic>>>.*")
                    .setRegexp(true)
                    .build());

            List<NamedTrafficStat> stats = response.getStatList().stream()
                    .map(this::toTrafficStat)
                    .toList();
            return parseUserTrafficStats(stats);
        } catch (Exception e) {
            log.error("获取 sing-box 流量统计失败, serverId={}", server.getId(), e);
            return Collections.emptyMap();
        }
    }

    @Override
    public String getStrategyName() {
        return "sing-box";
    }

    private NamedTrafficStat toTrafficStat(Stat stat) {
        return new NamedTrafficStat(stat.getName(), stat.getValue());
    }
}
