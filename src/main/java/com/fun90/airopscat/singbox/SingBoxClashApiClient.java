package com.fun90.airopscat.singbox;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.singbox.SingBoxConnectionsResponse;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshLocalPortForward;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@ApplicationScoped
public class SingBoxClashApiClient {

    static final String LOCAL_HOST = "127.0.0.1";
    static final String DEFAULT_CONFIG_PATH = "/etc/sing-box/config.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final SystemConfigService systemConfigService;

    @Inject
    public SingBoxClashApiClient(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    SingBoxClashApiClient(SystemConfigService systemConfigService, Object unused) {
        this.systemConfigService = systemConfigService;
    }

    public SingBoxConnectionsResponse getConnections(SshConnection connection) throws Exception {
        return execute(connection, "GET", "/connections", SingBoxConnectionsResponse.class);
    }

    public void deleteConnection(SshConnection connection, String connectionId) throws Exception {
        execute(connection, "DELETE", "/connections/" + connectionId, Void.class);
    }

    public void deleteAllConnections(SshConnection connection) throws Exception {
        execute(connection, "DELETE", "/connections", Void.class);
    }

    public void reloadConfig(SshConnection connection) throws Exception {
        reloadConfig(connection, DEFAULT_CONFIG_PATH);
    }

    public void reloadConfig(SshConnection connection, String configPath) throws Exception {
        execute(connection, "PUT", "/configs", Map.of("path", configPath), Void.class);
    }

    private <T> T execute(SshConnection connection, String method, String path, Class<T> responseType) throws Exception {
        return execute(connection, method, path, null, responseType);
    }

    private <T> T execute(SshConnection connection, String method, String path, Object requestBody, Class<T> responseType) throws Exception {
        String remoteHost = getRemoteHost();
        int remotePort = getRemotePort();
        int maxRetries = getMaxRetries();

        Exception lastError = null;
        int attempts = maxRetries + 1;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try (SshLocalPortForward portForward = connection.openLocalPortForward(0, remoteHost, remotePort)) {
                log.debug("Clash API 请求: method={}, path={}, attempt={}, localPort={}",
                        method, path, attempt, portForward.localPort());
                return doRequest(portForward.localPort(), method, path, requestBody, responseType);
            } catch (Exception e) {
                lastError = e;
                if (attempt >= attempts) {
                    throw e;
                }
                log.debug("Clash API 请求失败，准备重试: method={}, path={}, attempt={}", method, path, attempt, e);
            }
        }

        throw lastError == null ? new IllegalStateException("Clash API 请求失败: " + path) : lastError;
    }

    private <T> T doRequest(int localPort, String method, String path, Object requestBody, Class<T> responseType) throws IOException, InterruptedException {
        int timeoutSeconds = getTimeoutSeconds();
        String secret = getSecret();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://" + LOCAL_HOST + ":" + localPort + path))
                .timeout(Duration.ofSeconds(timeoutSeconds));

        if (secret != null && !secret.isBlank()) {
            builder.header("Authorization", "Bearer " + secret);
        }

        HttpRequest request = switch (method) {
            case "DELETE" -> builder.DELETE().build();
            case "PUT" -> builder.header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(requestBody)))
                    .build();
            default -> builder.GET().build();
        };

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Clash API 返回非 2xx 状态: " + response.statusCode()
                    + ", path=" + path + ", body=" + response.body());
        }

        if (responseType == Void.class || response.body() == null || response.body().isBlank()) {
            return null;
        }

        return MAPPER.readValue(response.body(), responseType);
    }

    public List<String> listConnectionIds(SshConnection connection) throws Exception {
        SingBoxConnectionsResponse resp = getConnections(connection);
        if (resp == null || resp.getConnections() == null) {
            return List.of();
        }
        return resp.getConnections().stream()
                .map(c -> c.getId())
                .filter(id -> id != null)
                .toList();
    }

    private String getRemoteHost() {
        return systemConfigService.getResolvedValue("airopscat.sing-box.clash-api.host");
    }

    private int getRemotePort() {
        return systemConfigService.getIntValue("airopscat.sing-box.clash-api.port", 19191);
    }

    private String getSecret() {
        return systemConfigService.getResolvedValue("airopscat.sing-box.clash-api.secret");
    }

    private int getTimeoutSeconds() {
        return Math.max(systemConfigService.getIntValue("airopscat.sing-box.clash-api.timeout-seconds", 5), 1);
    }

    private int getMaxRetries() {
        return Math.max(systemConfigService.getIntValue("airopscat.sing-box.clash-api.max-retries", 1), 0);
    }
}
