package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.service.ssh.SshConnection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrafficStatsTaskTest {

    @Test
    void shouldCollectEachRuntimeTargetServerOnce() {
        TestableTrafficStatsTask task = new TestableTrafficStatsTask();
        task.servers = List.of(server(100L), server(200L));

        task.collectUserTrafficStats();

        assertEquals(List.of(100L, 200L), task.collectedServerIds);
    }

    @Test
    void shouldStopWhenNoRuntimeTargetServerExists() {
        TestableTrafficStatsTask task = new TestableTrafficStatsTask();

        task.collectUserTrafficStats();

        assertEquals(List.of(), task.collectedServerIds);
    }

    private static Server server(Long id) {
        Server server = new Server();
        server.setId(id);
        server.setName("server-" + id);
        server.setIp("127.0.0.1");
        return server;
    }

    static class TestableTrafficStatsTask extends TrafficStatsTask {
        List<Server> servers = List.of();
        List<Long> collectedServerIds = new ArrayList<>();

        @Override
        List<Server> loadRuntimeTargetServers() {
            return servers;
        }

        @Override
        TrafficCollectResult collectServerTrafficStats(SshConnection connection, Server server) {
            collectedServerIds.add(server.getId());
            return new TrafficCollectResult(0, 0, 0);
        }

        @Override
        SshConnection createConnection(Server server) {
            return new NoopSshConnection();
        }
    }

    static class NoopSshConnection implements SshConnection {
        @Override
        public com.fun90.airopscat.model.dto.CommandResult executeCommand(String command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String readRemoteFile(String remotePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeRemoteFile(String remotePath, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.io.InputStream getRemoteFileInputStream(String remotePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.io.OutputStream getRemoteFileOutputStream(String remotePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getConnectionInfo() {
            return "test";
        }

        @Override
        public int forwardLocalPort(int localPort, String remoteHost, int remotePort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.fun90.airopscat.service.ssh.SshLocalPortForward openLocalPortForward(
                int preferredLocalPort, String remoteHost, int remotePort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelLocalPortForward(int localPort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
