package com.fun90.airopscat.service.traffic;

import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.service.ssh.SshConnection;

import java.util.Map;

public interface TrafficStatsCollector {

    Map<String, UserTrafficStats> collectUserTrafficStats(SshConnection connection, Server server, ServerConfig serverConfig);

    String getStrategyName();
}
