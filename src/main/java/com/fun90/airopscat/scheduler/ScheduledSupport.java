package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.entity.Server;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;

@ApplicationScoped
public class ScheduledSupport {

    @Inject
    com.fun90.airopscat.service.ssh.ServerSshConfigFactory serverSshConfigFactory;

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
        return serverSshConfigFactory.create(server, 10000);
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
