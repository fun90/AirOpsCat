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
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import jakarta.enterprise.context.ApplicationScoped;
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
    private static final String STATS_SERVICE_NAME = "v2ray.core.app.stats.command.StatsService";
    private static final MethodDescriptor<QueryStatsRequest, QueryStatsResponse> QUERY_STATS_METHOD =
            MethodDescriptor.<QueryStatsRequest, QueryStatsResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(STATS_SERVICE_NAME, "QueryStats"))
                    .setRequestMarshaller(ProtoUtils.marshaller(QueryStatsRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(QueryStatsResponse.getDefaultInstance()))
                    .build();

    @Override
    public Map<String, UserTrafficStats> collectUserTrafficStats(SshConnection connection, Server server, ServerConfig serverConfig) {
        int apiPort = DEFAULT_API_PORT;
        Integer localPort = null;
        ManagedChannel channel = null;

        try {
            localPort = connection.forwardLocalPort(0, API_HOST, apiPort);
            channel = ManagedChannelBuilder.forAddress(API_HOST, localPort)
                    .usePlaintext()
                    .build();

            QueryStatsResponse response = ClientCalls.blockingUnaryCall(
                    channel,
                    QUERY_STATS_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                    QueryStatsRequest.newBuilder()
                            .setReset(true)
                            .addPatterns("user>>>.*>>>traffic>>>.*")
                            .setRegexp(true)
                            .build()
            );

            List<NamedTrafficStat> stats = response.getStatList().stream()
                    .map(this::toTrafficStat)
                    .toList();
            return parseUserTrafficStats(stats);
        } catch (Exception e) {
            log.error("获取 sing-box 流量统计失败, serverId={}, apiPort={}", server.getId(), apiPort, e);
            return Collections.emptyMap();
        } finally {
            if (channel != null) {
                channel.shutdownNow();
            }
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
