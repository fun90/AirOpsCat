package com.fun90.airopscat.singbox;

import com.fun90.airopscat.proto.v2rayapi.QueryStatsRequest;
import com.fun90.airopscat.proto.v2rayapi.QueryStatsResponse;
import com.fun90.airopscat.proto.v2rayapi.StatsServiceGrpc;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshLocalPortForward;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.TimeUnit;

@Slf4j
@ApplicationScoped
public class SingBoxGrpcQueryClient {

    static final String API_HOST = "127.0.0.1";
    static final int DEFAULT_API_PORT = 101;

    private final int timeoutSeconds;
    private final int maxRetries;
    private final GrpcQueryExecutor grpcQueryExecutor;

    @Inject
    public SingBoxGrpcQueryClient(
            @ConfigProperty(name = "airopscat.sing-box.grpc.timeout-seconds", defaultValue = "10") int timeoutSeconds,
            @ConfigProperty(name = "airopscat.sing-box.grpc.max-retries", defaultValue = "1") int maxRetries) {
        this(timeoutSeconds, maxRetries, new DefaultGrpcQueryExecutor());
    }

    SingBoxGrpcQueryClient(int timeoutSeconds, int maxRetries, GrpcQueryExecutor grpcQueryExecutor) {
        this.timeoutSeconds = Math.max(timeoutSeconds, 1);
        this.maxRetries = Math.max(maxRetries, 0);
        this.grpcQueryExecutor = grpcQueryExecutor;
    }

    public QueryStatsResponse queryStats(SshConnection connection, QueryStatsRequest request) throws Exception {
        Exception lastError = null;
        int attempts = maxRetries + 1;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try (SshLocalPortForward portForward = connection.openLocalPortForward(0, API_HOST, DEFAULT_API_PORT)) {
                log.debug("开始查询 sing-box gRPC 统计, attempt={}, localPort={}, remote={}:{}",
                        attempt, portForward.localPort(), portForward.remoteHost(), portForward.remotePort());
                return grpcQueryExecutor.query(portForward.localPort(), timeoutSeconds, request);
            } catch (Exception e) {
                lastError = e;
                if (attempt >= attempts) {
                    throw e;
                }
                log.debug("sing-box gRPC 查询失败，准备重试, attempt={}, maxAttempts={}", attempt, attempts, e);
            }
        }

        throw lastError == null ? new IllegalStateException("sing-box gRPC 查询失败") : lastError;
    }

    interface GrpcQueryExecutor {
        QueryStatsResponse query(int localPort, int timeoutSeconds, QueryStatsRequest request) throws Exception;
    }

    static class DefaultGrpcQueryExecutor implements GrpcQueryExecutor {

        @Override
        public QueryStatsResponse query(int localPort, int timeoutSeconds, QueryStatsRequest request) throws Exception {
            ManagedChannel channel = null;
            try {
                channel = ManagedChannelBuilder.forAddress(API_HOST, localPort)
                        .usePlaintext()
                        .build();
                return StatsServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(timeoutSeconds, TimeUnit.SECONDS)
                        .queryStats(request);
            } finally {
                shutdownChannel(channel);
            }
        }

        private void shutdownChannel(ManagedChannel channel) {
            if (channel == null) {
                return;
            }
            channel.shutdownNow();
            try {
                if (!channel.awaitTermination(3, TimeUnit.SECONDS)) {
                    log.debug("sing-box gRPC channel 未在预期时间内关闭");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("等待 sing-box gRPC channel 关闭时被中断", e);
            }
        }
    }
}
