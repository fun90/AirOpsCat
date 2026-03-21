package com.fun90.airopscat.service.traffic;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public abstract class AbstractV2RayApiTrafficStatsCollector implements TrafficStatsCollector {

    protected Map<String, UserTrafficStats> parseUserTrafficStats(List<NamedTrafficStat> stats) {
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

    public record NamedTrafficStat(String name, long value) {
    }
}
