package com.fun90.airopscat.service.singbox;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Sing-box Clash API 客户端，通过 SSH 执行 curl 调用远端 Clash API。
 * 可供流量统计、限速等功能复用。
 */
@Slf4j
@ApplicationScoped
public class SingBoxClashApiClient {

    @ConfigProperty(name = "airopscat.sing-box.clash-api.port", defaultValue = "19191")
    int clashApiPort;

    /**
     * 查询当前所有活跃连接（GET /connections）
     */
    public ClashConnectionsResponse queryConnections(SshConnection connection) throws Exception {
        String output = get(connection, "/connections");
        return JsonUtil.toObject(output, ClashConnectionsResponse.class);
    }

    /**
     * 通过 SSH 执行 curl 调用指定 Clash API 路径，返回响应体字符串。
     *
     * @throws Exception 命令执行失败或输出为空时抛出
     */
    public String get(SshConnection connection, String path) throws Exception {
        String command = String.format("curl -s http://127.0.0.1:%d%s", clashApiPort, path);
        CommandResult result = connection.executeCommand(command);
        if (!result.isSuccess()) {
            throw new RuntimeException("clash API 请求失败, path=" + path + ", stderr=" + result.getStderr());
        }
        String output = result.getStdout();
        if (output == null || output.isBlank()) {
            throw new RuntimeException("clash API 返回空响应, path=" + path);
        }
        return output;
    }

    // ─── DTO records ────────────────────────────────────────────────────────────

    public record ClashConnectionsResponse(
            List<ClashConnection> connections,
            Long downloadTotal,
            Long uploadTotal,
            Long memory
    ) {}

    public record ClashConnection(
            String id,
            Long upload,
            Long download,
            ClashConnectionMetadata metadata,
            List<String> chains,
            String rule,
            String rulePayload,
            String start
    ) {}

    public record ClashConnectionMetadata(
            String type,
            String authUser,
            String network,
            String host,
            String sourceIP,
            String sourcePort,
            String destinationIP,
            String destinationPort,
            String dnsMode,
            String processPath
    ) {}
}
