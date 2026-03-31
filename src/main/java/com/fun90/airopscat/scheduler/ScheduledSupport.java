package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.entity.Server;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;

@ApplicationScoped
public class ScheduledSupport {

    public boolean isInvalidServer(Server server, LocalDate today) {
        if (server == null) {
            return true;
        }
        if (server.getDisabled() != null && server.getDisabled() == 1) {
            return true;
        }
        if (server.getExpireDate() != null && today.isAfter(server.getExpireDate())) {
            return true;
        }
        return server.getExternal() != null && server.getExternal() != 0;
    }

    public SshConfig buildSshConfig(Server server) {
        SshConfig sshConfig = new SshConfig();
        sshConfig.setHost(server.getIp());
        sshConfig.setPort(server.getSshPort() != null ? server.getSshPort() : 22);
        sshConfig.setUsername(server.getUsername());
        sshConfig.setTimeout(10000);

        String auth = server.getAuth();
        if ("PASSWORD".equalsIgnoreCase(server.getAuthType())) {
            sshConfig.setPassword(auth);
        } else {
            sshConfig.setPrivateKeyContent(auth);
        }

        return sshConfig;
    }

    public String resolveParentDirectory(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int separatorIndex = path.lastIndexOf('/');
        if (separatorIndex < 1) {
            return null;
        }
        return path.substring(0, separatorIndex);
    }
}
