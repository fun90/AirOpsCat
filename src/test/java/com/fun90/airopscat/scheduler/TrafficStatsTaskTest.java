package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.service.ssh.SshConnection;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrafficStatsTaskTest {

    @Test
    void shouldGroupConfigsByServerWhenCollectingTrafficStats() {
        TestableTrafficStatsTask task = new TestableTrafficStatsTask();
        Server server = new Server();
        server.setId(100L);
        server.setName("server-100");
        server.setExpireDate(LocalDate.now().plusDays(1));
        server.setIp("127.0.0.1");

        ServerConfig first = new ServerConfig();
        first.setId(1L);
        first.setServerId(100L);
        first.setConfigType("sing-box");
        first.setEnabled(1);

        ServerConfig second = new ServerConfig();
        second.setId(2L);
        second.setServerId(100L);
        second.setConfigType("singbox");
        second.setEnabled(1);

        task.serverConfigs = List.of(first, second);
        task.serverMap = Map.of(100L, server);

        task.collectUserTrafficStats();

        assertEquals(1, task.collectInvocationCount.get());
        assertEquals(2, task.lastCollectedConfigCount);
    }

    static class TestableTrafficStatsTask extends TrafficStatsTask {
        List<ServerConfig> serverConfigs = List.of();
        Map<Long, Server> serverMap = Map.of();
        AtomicInteger collectInvocationCount = new AtomicInteger();
        int lastCollectedConfigCount;

        TestableTrafficStatsTask() {
            this.scheduledSupport = new ScheduledSupport();
        }

        @Override
        List<ServerConfig> loadServerConfigs() {
            return serverConfigs;
        }

        @Override
        Server loadServer(Long serverId) {
            return serverMap.get(serverId);
        }

        @Override
        Set<String> getRegisteredCollectorTypes() {
            return Set.of("sing-box", "singbox");
        }

        @Override
        TrafficCollectResult collectServerTrafficStats(SshConnection connection, Server server, List<ServerConfig> serverConfigs) {
            collectInvocationCount.incrementAndGet();
            lastCollectedConfigCount = serverConfigs.size();
            return new TrafficCollectResult(0, 0, 0);
        }

        @Override
        SshConnection createConnection(Server server) {
            return new SshConnection() {
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
                public com.fun90.airopscat.service.ssh.SshLocalPortForward openLocalPortForward(int preferredLocalPort, String remoteHost, int remotePort) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void cancelLocalPortForward(int localPort) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
