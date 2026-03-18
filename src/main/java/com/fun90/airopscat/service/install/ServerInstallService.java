package com.fun90.airopscat.service.install;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.dto.install.InstallScriptDto;
import com.fun90.airopscat.model.dto.install.ServerInstallStepResultDto;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.service.ServerService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
public class ServerInstallService {

    private static final Pattern SCRIPT_NAME_PATTERN = Pattern.compile("^(\\d+)-(.+)\\.sh$");
    private static final Pattern TITLE_PATTERN = Pattern.compile("^#\\s*@title\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile("^#\\s*@description\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    @ConfigProperty(name = "airopscat.install.scripts.dir", defaultValue = "./config/install")
    String installScriptsDir;

    @ConfigProperty(name = "airopscat.install.remote-work-dir", defaultValue = "/tmp/airopscat-installer")
    String remoteWorkDir;

    @ConfigProperty(name = "airopscat.domain", defaultValue = "")
    String airopscatDomain;

    @ConfigProperty(name = "airopscat.api.token", defaultValue = "")
    String airopscatApiToken;

    @Inject
    ServerService serverService;

    @Inject
    SshConnectionService sshConnectionService;

    public List<InstallScriptDto> listScripts() {
        Path dir = Path.of(installScriptsDir).normalize();
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return List.of();
        }

        try {
            List<InstallScriptDto> scripts = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .map(this::toScriptDto)
                        .flatMap(Optional::stream)
                        .sorted(Comparator.comparing(InstallScriptDto::getOrder).thenComparing(InstallScriptDto::getFileName))
                        .forEach(scripts::add);
            }
            return scripts;
        } catch (IOException e) {
            throw new IllegalStateException("读取装机脚本目录失败: " + dir, e);
        }
    }

    public ServerInstallStepResultDto executeScript(Long serverId, String scriptName) {
        LocalDateTime startedAt = LocalDateTime.now();
        ServerInstallStepResultDto result = new ServerInstallStepResultDto();
        result.setScriptName(scriptName);
        result.setStartedAt(startedAt);

        InstallScriptDto script = findScript(scriptName)
                .orElseThrow(() -> new IllegalArgumentException("未找到装机脚本: " + scriptName));
        result.setStepTitle(script.getTitle());

        Server server = Optional.ofNullable(serverService.getServerById(serverId))
                .orElseThrow(() -> new IllegalArgumentException("服务器不存在: " + serverId));

        String scriptContent = readScriptContent(scriptName).replace("\r\n", "\n");

        try (SshConnection connection = sshConnectionService.createConnection(buildSshConfig(server))) {
            connection.executeCommand("mkdir -p " + quoteShell(remoteWorkDir));

            String remoteScriptPath = remoteWorkDir + "/" + scriptName;
            connection.writeRemoteFile(remoteScriptPath, scriptContent);

            String command = "chmod +x " + quoteShell(remoteScriptPath)
                    + " && AIROPSCAT_STEP_NAME=" + quoteShell(script.getTitle())
                    + " server_ip=" + quoteShell(defaultString(server.getIp()))
                    + " server_host=" + quoteShell(defaultString(server.getHost()))
                    + " airopscat_domain=" + quoteShell(defaultString(airopscatDomain))
                    + " airopscat_api_token=" + quoteShell(defaultString(airopscatApiToken))
                    + " /bin/bash " + quoteShell(remoteScriptPath);

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

    private Optional<InstallScriptDto> findScript(String scriptName) {
        return listScripts().stream()
                .filter(script -> Objects.equals(script.getFileName(), scriptName))
                .findFirst();
    }

    private String readScriptContent(String scriptName) {
        Path file = Path.of(installScriptsDir, scriptName).normalize();
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("装机脚本不存在: " + scriptName);
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取装机脚本失败: " + scriptName, e);
        }
    }

    private Optional<InstallScriptDto> toScriptDto(Path path) {
        String fileName = path.getFileName().toString();
        Matcher matcher = SCRIPT_NAME_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        InstallScriptDto dto = new InstallScriptDto();
        dto.setOrder(Integer.parseInt(matcher.group(1)));
        dto.setFileName(fileName);
        dto.setTitle(toFriendlyTitle(matcher.group(2)));
        dto.setDescription("按顺序执行的安装步骤");
        enrichMetadata(path, dto);
        return Optional.of(dto);
    }

    private void enrichMetadata(Path path, InstallScriptDto dto) {
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

    private SshConfig buildSshConfig(Server server) {
        SshConfig sshConfig = new SshConfig();
        sshConfig.setHost(server.getIp());
        sshConfig.setPort(server.getSshPort());
        sshConfig.setUsername(server.getUsername() == null || server.getUsername().isBlank() ? "root" : server.getUsername());

        boolean isPassword = "PASSWORD".equalsIgnoreCase(server.getAuthType())
                || "password".equalsIgnoreCase(server.getAuthType());
        if (isPassword) {
            sshConfig.setPassword(server.getAuth());
        } else {
            sshConfig.setPrivateKeyContent(server.getAuth());
        }
        return sshConfig;
    }

    private String quoteShell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
