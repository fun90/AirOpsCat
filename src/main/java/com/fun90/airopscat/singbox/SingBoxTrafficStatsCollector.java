package com.fun90.airopscat.singbox;

import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.proto.v2rayapi.QueryStatsRequest;
import com.fun90.airopscat.proto.v2rayapi.QueryStatsResponse;
import com.fun90.airopscat.proto.v2rayapi.Stat;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.model.dto.UserTrafficStats;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@ApplicationScoped
public class SingBoxTrafficStatsCollector {

    @Inject
    SingBoxGrpcQueryClient grpcQueryClient;

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

    private NamedTrafficStat toTrafficStat(Stat stat) {
        return new NamedTrafficStat(stat.getName(), stat.getValue());
    }

    private Map<String, UserTrafficStats> parseUserTrafficStats(List<NamedTrafficStat> stats) {
        Map<String, UserTrafficStats> userTrafficMap = new HashMap<>();
        Map<String, Long> uplinkMap = new HashMap<>();
        Map<String, Long> downlinkMap = new HashMap<>();

        for (NamedTrafficStat stat : stats) {
            if (stat == null || stat.name() == null) {
                continue;
            }

            String name = stat.name();
            if (!name.startsWith("user>>>") || !name.contains(">>>traffic>>>")) {
                continue;
            }

            String[] parts = name.split(">>>");
            if (parts.length < 4) {
                continue;
            }

            String accountNo = parts[1];
            String trafficType = parts[3];
            if ("uplink".equals(trafficType)) {
                uplinkMap.put(accountNo, stat.value());
            } else if ("downlink".equals(trafficType)) {
                downlinkMap.put(accountNo, stat.value());
            }
        }

        Set<String> allUsers = new HashSet<>();
        allUsers.addAll(uplinkMap.keySet());
        allUsers.addAll(downlinkMap.keySet());

        for (String accountNo : allUsers) {
            long uploadBytes = uplinkMap.getOrDefault(accountNo, 0L);
            long downloadBytes = downlinkMap.getOrDefault(accountNo, 0L);
            if (uploadBytes > 0 || downloadBytes > 0) {
                userTrafficMap.put(accountNo, new UserTrafficStats(uploadBytes, downloadBytes));
            }
        }

        return userTrafficMap;
    }

    private record NamedTrafficStat(String name, long value) {
    }
}
