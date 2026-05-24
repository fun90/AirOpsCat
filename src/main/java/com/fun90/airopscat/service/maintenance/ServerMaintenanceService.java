package com.fun90.airopscat.service.maintenance;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.maintenance.MaintenanceScriptDto;
import com.fun90.airopscat.model.dto.maintenance.ServerMaintenanceStepResultDto;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.service.ServerHostService;
import com.fun90.airopscat.service.ServerService;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.service.ssh.ServerSshConfigFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ServerMaintenanceService {

    private static final Pattern SCRIPT_NAME_PATTERN = Pattern.compile("^(\\d+)-(.+)\\.sh$");
    private static final Pattern TITLE_PATTERN = Pattern.compile("^#\\s*@title\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile("^#\\s*@description\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    @Inject
    ServerService serverService;

    @Inject
    ServerHostService serverHostService;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    ServerSshConfigFactory serverSshConfigFactory;

    @Inject
    SystemConfigService systemConfigService;

    public List<MaintenanceScriptDto> listScripts() {
        Path dir = Path.of(systemConfigService.getResolvedValue("airopscat.install.scripts.dir")).normalize();
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return List.of();
        }

        try {
            List<MaintenanceScriptDto> scripts = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .map(this::toScriptDto)
                        .flatMap(Optional::stream)
                        .sorted(Comparator.comparing(MaintenanceScriptDto::getOrder).thenComparing(MaintenanceScriptDto::getFileName))
                        .forEach(scripts::add);
            }
            return scripts;
        } catch (IOException e) {
            throw new IllegalStateException("读取运维脚本目录失败: " + dir, e);
        }
    }

    public String getScriptContent(String scriptName) {
        return readScriptContent(scriptName);
    }

    public void saveScriptContent(String scriptName, String content) {
        Path dir = Path.of(systemConfigService.getResolvedValue("airopscat.install.scripts.dir")).normalize();
        Path file = dir.resolve(scriptName).normalize();
        if (!file.startsWith(dir)) {
            throw new IllegalArgumentException("非法脚本路径: " + scriptName);
        }
        Matcher matcher = SCRIPT_NAME_PATTERN.matcher(scriptName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("脚本文件名不符合规范: " + scriptName);
        }
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("保存脚本失败: " + scriptName, e);
        }
    }

    public ServerMaintenanceStepResultDto executeScript(Long serverId, String scriptName) {
        LocalDateTime startedAt = LocalDateTime.now();
        ServerMaintenanceStepResultDto result = new ServerMaintenanceStepResultDto();
        result.setScriptName(scriptName);
        result.setStartedAt(startedAt);

        MaintenanceScriptDto script = findScript(scriptName)
                .orElseThrow(() -> new IllegalArgumentException("未找到运维脚本: " + scriptName));
        result.setStepTitle(script.getTitle());

        Server server = Optional.ofNullable(serverService.getServerById(serverId))
                .orElseThrow(() -> new IllegalArgumentException("服务器不存在: " + serverId));

        String scriptContent = readScriptContent(scriptName).replace("\r\n", "\n");

        try (SshConnection connection = sshConnectionService.createConnection(serverSshConfigFactory.create(server))) {
            String remoteWorkDir = getRemoteWorkDir();
            connection.executeCommand("mkdir -p " + quoteShell(remoteWorkDir));

            String remoteScriptPath = remoteWorkDir + "/" + scriptName;
            connection.writeRemoteFile(remoteScriptPath, scriptContent);

            String command = "chmod +x " + quoteShell(remoteScriptPath)
                    + " && /bin/bash -lc " + quoteShell(buildScriptExecutionCommand(script.getTitle(), server, remoteScriptPath));

            CommandResult commandResult = connection.executeCommand(command);
            result.setSuccess(commandResult.isSuccess());
            result.setExitStatus(commandResult.getExitStatus());
            result.setStdout(commandResult.getStdout());
            result.setStderr(commandResult.getStderr());
            result.setMessage(commandResult.isSuccess() ? "执行成功" : "执行失败");
        } catch (Exception e) {
            result.setSuccess(false);
            result.setExitStatus(-1);
            result.setStdout("");
            result.setStderr(e.getMessage());
            result.setMessage("执行失败: " + e.getMessage());
        }

        result.setFinishedAt(LocalDateTime.now());
        result.setDurationMs(Duration.between(startedAt, result.getFinishedAt()).toMillis());
        return result;
    }

    private Optional<MaintenanceScriptDto> findScript(String scriptName) {
        return listScripts().stream()
                .filter(script -> Objects.equals(script.getFileName(), scriptName))
                .findFirst();
    }

    private String readScriptContent(String scriptName) {
        Path file = Path.of(systemConfigService.getResolvedValue("airopscat.install.scripts.dir"), scriptName).normalize();
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("运维脚本不存在: " + scriptName);
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取运维脚本失败: " + scriptName, e);
        }
    }

    private Optional<MaintenanceScriptDto> toScriptDto(Path path) {
        String fileName = path.getFileName().toString();
        Matcher matcher = SCRIPT_NAME_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        MaintenanceScriptDto dto = new MaintenanceScriptDto();
        dto.setOrder(Integer.parseInt(matcher.group(1)));
        dto.setFileName(fileName);
        dto.setTitle(toFriendlyTitle(matcher.group(2)));
        dto.setDescription("按顺序执行的运维步骤");
        enrichMetadata(path, dto);
        return Optional.of(dto);
    }

    private void enrichMetadata(Path path, MaintenanceScriptDto dto) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines.stream().limit(12).toList()) {
                Matcher titleMatcher = TITLE_PATTERN.matcher(line);
                if (titleMatcher.matches()) {
                    dto.setTitle(titleMatcher.group(1).trim());
                }

                Matcher descriptionMatcher = DESCRIPTION_PATTERN.matcher(line);
                if (descriptionMatcher.matches()) {
                    dto.setDescription(descriptionMatcher.group(1).trim());
                }
            }
        } catch (IOException ignored) {
        }
    }

    private String toFriendlyTitle(String rawName) {
        return rawName.replace('-', ' ')
                .replace('_', ' ')
                .trim();
    }

    private String quoteShell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String buildScriptExecutionCommand(String stepTitle, Server server, String remoteScriptPath) {
        List<String> hosts = serverHostService.getResolvedHosts(server);
        return "AIROPSCAT_STEP_NAME=" + quoteShell(stepTitle)
                + "; server_ip=" + quoteShell(defaultString(server.getIp()))
                + "; server_host=" + quoteShell(defaultString(serverHostService.resolvePrimaryHost(server)))
                + "; server_ssh_port=" + quoteShell(server.getSshPort() + "")
                + "; airopscat_domain=" + quoteShell(defaultString(getDomain()))
                + "; airopscat_api_token=" + quoteShell(defaultString(getApiToken()))
                + "; bandwidth_day=" + quoteShell(resolveBandwidthDay(server))
                + "; declare -a server_hosts=" + toBashArray(hosts)
                + "; export AIROPSCAT_STEP_NAME server_ip server_host airopscat_domain airopscat_api_token bandwidth_day"
                + "; source " + quoteShell(remoteScriptPath);
    }

    private String resolveBandwidthDay(Server server) {
        if (server.getBandwidthDate() == null) {
            return "1";
        }
        return String.valueOf(server.getBandwidthDate().getDayOfMonth());
    }

    private String getRemoteWorkDir() {
        return systemConfigService.getResolvedValue("airopscat.install.remote-work-dir");
    }

    private String getDomain() {
        return systemConfigService.getResolvedValue("airopscat.domain");
    }

    private String getApiToken() {
        return systemConfigService.getResolvedValue("airopscat.api.token");
    }

    private String toBashArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "()";
        }
        StringBuilder builder = new StringBuilder("(");
        for (String value : values) {
            builder.append(quoteShell(defaultString(value))).append(' ');
        }
        builder.append(')');
        return builder.toString();
    }
}
