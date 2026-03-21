package com.fun90.airopscat.service.traffic.impl;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.proto.v2rayapi.QueryStatsRequest;
import com.fun90.airopscat.proto.v2rayapi.QueryStatsResponse;
import com.fun90.airopscat.proto.v2rayapi.StatsServiceGrpc;
import com.fun90.airopscat.proto.v2rayapi.Stat;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.traffic.AbstractV2RayApiTrafficStatsCollector;
import com.fun90.airopscat.service.traffic.UserTrafficStats;
import io.quarkus.grpc.GrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@ApplicationScoped
@SupportedCores(value = {"sing-box", "singbox"}, priority = 1, description = "Sing-box 流量统计采集策略")
public class SingBoxTrafficStatsCollector extends AbstractV2RayApiTrafficStatsCollector {

    private static final String API_HOST = "127.0.0.1";
    private static final int DEFAULT_API_PORT = 101;

    @GrpcClient("sing-box")
    StatsServiceGrpc.StatsServiceBlockingStub statsServiceClient;

    @ConfigProperty(name = "airopscat.sing-box.grpc.local-port", defaultValue = "11011")
    int localGrpcPort;

    @Override
    public Map<String, UserTrafficStats> collectUserTrafficStats(SshConnection connection, Server server, ServerConfig serverConfig) {
        int apiPort = DEFAULT_API_PORT;
        Integer localPort = null;

        try {
            localPort = connection.forwardLocalPort(localGrpcPort, API_HOST, apiPort);

            QueryStatsResponse response = statsServiceClient
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .queryStats(QueryStatsRequest.newBuilder()
                            .setReset(true)
                            .addPatterns("user>>>.*>>>traffic>>>.*")
                            .setRegexp(true)
                            .build());

            List<NamedTrafficStat> stats = response.getStatList().stream()
                    .map(this::toTrafficStat)
                    .toList();
            return parseUserTrafficStats(stats);
        } catch (Exception e) {
            log.error("获取 sing-box 流量统计失败, serverId={}, apiPort={}, localGrpcPort={}", server.getId(), apiPort, localGrpcPort, e);
            return Collections.emptyMap();
        } finally {
            if (localPort != null) {
                try {
                    connection.cancelLocalPortForward(localPort);
                } catch (Exception e) {
                    log.debug("关闭 sing-box 本地端口转发失败, localPort={}", localPort, e);
                }
            }
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
