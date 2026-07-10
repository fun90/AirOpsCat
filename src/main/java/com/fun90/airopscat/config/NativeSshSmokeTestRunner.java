package com.fun90.airopscat.config;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.service.ssh.impl.JschConnection;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

@Slf4j
@ApplicationScoped
public class NativeSshSmokeTestRunner {

    void onStart(@Observes StartupEvent event) {
        if (!SshSmokeTestMode.isEnabled()) {
            return;
        }

        int exitCode = 1;
        try {
            SmokeConfig smokeConfig = loadConfig();
            String privateKeyContent = Files.readString(Path.of(smokeConfig.privateKeyPath()), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim();
            log.info("AirOpsCat Native SSH smoke test started: target={}@{}:{}, keyLength={}, keyFingerprint={}",
                    smokeConfig.username(),
                    smokeConfig.host(),
                    smokeConfig.port(),
                    privateKeyContent.length(),
                    fingerprint(privateKeyContent));

            SshConfig sshConfig = new SshConfig();
            sshConfig.setHost(smokeConfig.host());
            sshConfig.setPort(smokeConfig.port());
            sshConfig.setUsername(smokeConfig.username());
            sshConfig.setPrivateKeyContent(privateKeyContent);
            sshConfig.setTimeout(smokeConfig.timeoutMillis());

            String command = """
                    set -eu
                    printf 'marker=%%s\\nwhoami=%%s\\nhostname=%%s\\nepoch=%%s\\n' %s "$(whoami)" "$(hostname)" "$(date +%%s)" > /tmp/airopscat-full-native-ssh-proof.txt
                    cat /tmp/airopscat-full-native-ssh-proof.txt
                    """.formatted(shellQuote(smokeConfig.marker()));

            try (JschConnection connection = new JschConnection(sshConfig)) {
                CommandResult result = connection.executeCommand(command);
                log.info("AirOpsCat Native SSH smoke test exitStatus={}", result.getExitStatus());
                log.info("AirOpsCat Native SSH smoke test stdout:\n{}", result.getStdout());
                if (result.getStderr() != null && !result.getStderr().isBlank()) {
                    log.warn("AirOpsCat Native SSH smoke test stderr:\n{}", result.getStderr());
                }
                exitCode = result.isSuccess() ? 0 : 2;
            }
        } catch (Exception e) {
            log.error("AirOpsCat Native SSH smoke test failed", e);
            exitCode = 1;
        } finally {
            Quarkus.asyncExit(exitCode);
        }
    }

    private SmokeConfig loadConfig() {
        Config config = ConfigProvider.getConfig();
        String host = required(config, "airopscat.ssh-smoke.host");
        String username = config.getOptionalValue("airopscat.ssh-smoke.username", String.class).orElse("root");
        String privateKeyPath = required(config, "airopscat.ssh-smoke.private-key-path");
        int port = config.getOptionalValue("airopscat.ssh-smoke.port", Integer.class).orElse(22);
        int timeoutMillis = config.getOptionalValue("airopscat.ssh-smoke.timeout-millis", Integer.class).orElse(10_000);
        String marker = config.getOptionalValue("airopscat.ssh-smoke.marker", String.class)
                .orElse("airopscat-full-native-" + System.currentTimeMillis());
        return new SmokeConfig(host, port, username, privateKeyPath, timeoutMillis, marker);
    }

    private String required(Config config, String name) {
        return config.getOptionalValue(name, String.class)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("缺少配置: " + name));
    }

    private String fingerprint(String content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest).substring(0, 16);
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private record SmokeConfig(String host,
                               int port,
                               String username,
                               String privateKeyPath,
                               int timeoutMillis,
                               String marker) {
    }
}
