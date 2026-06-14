package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.entity.Server;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoreConfigCleanupTaskTest {

    @Test
    void shouldUseFixedSingBoxDirectoryForEveryRuntimeTargetServer() {
        TestableCoreConfigCleanupTask task = new TestableCoreConfigCleanupTask();
        task.scheduledSupport = new ScheduledSupport();
        task.servers = List.of(server(1L), server(2L));

        task.cleanupOldCoreConfigBackupFiles();

        assertEquals(List.of("/etc/sing-box", "/etc/sing-box"), task.configDirs);
    }

    @Test
    void shouldResolveSingBoxConfigParentDirectory() {
        ScheduledSupport support = new ScheduledSupport();

        assertEquals("/etc/sing-box",
                support.resolveParentDirectory(CoreConfigCleanupTask.SING_BOX_CONFIG_PATH));
    }

    private static Server server(Long id) {
        Server server = new Server();
        server.setId(id);
        server.setName("server-" + id);
        return server;
    }

    static class TestableCoreConfigCleanupTask extends CoreConfigCleanupTask {
        List<Server> servers = List.of();
        List<String> configDirs = new ArrayList<>();

        @Override
        List<Server> loadRuntimeTargetServers() {
            return servers;
        }

        @Override
        int cleanupServerBackupFiles(Server server, String configDir) {
            configDirs.add(configDir);
            return 0;
        }
    }
}
