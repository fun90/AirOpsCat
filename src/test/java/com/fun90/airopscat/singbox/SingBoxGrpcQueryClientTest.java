package com.fun90.airopscat.singbox;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.proto.v2rayapi.QueryStatsRequest;
import com.fun90.airopscat.proto.v2rayapi.QueryStatsResponse;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshLocalPortForward;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SingBoxGrpcQueryClientTest {

    @Test
    void shouldRetryWithNewForwardAndCloseHandles() throws Exception {
        FakeSshConnection connection = new FakeSshConnection(List.of(18081, 18082));
        QueryStatsResponse expected = QueryStatsResponse.getDefaultInstance();
        AtomicInteger attempts = new AtomicInteger();
        List<Integer> queriedPorts = new ArrayList<>();
        SingBoxGrpcQueryClient client = new SingBoxGrpcQueryClient(10, 1, (localPort, timeoutSeconds, request) -> {
            queriedPorts.add(localPort);
            if (attempts.getAndIncrement() == 0) {
                throw new IOException("first attempt failed");
            }
            return expected;
        });

        QueryStatsResponse response = client.queryStats(connection, QueryStatsRequest.getDefaultInstance());

        assertSame(expected, response);
        assertEquals(List.of(18081, 18082), queriedPorts);
        assertEquals(2, connection.openCount.get());
        assertEquals(2, connection.closedForwards.get());
    }

    @Test
    void shouldFailAfterRetryLimitAndCloseAllHandles() {
        FakeSshConnection connection = new FakeSshConnection(List.of(19081, 19082));
        SingBoxGrpcQueryClient client = new SingBoxGrpcQueryClient(10, 1, (localPort, timeoutSeconds, request) -> {
            throw new IOException("always fail");
        });

        assertThrows(IOException.class, () -> client.queryStats(connection, QueryStatsRequest.getDefaultInstance()));
        assertEquals(2, connection.openCount.get());
        assertEquals(2, connection.closedForwards.get());
    }

    private static final class FakeSshConnection implements SshConnection {
        private final List<Integer> ports;
        private final AtomicInteger portIndex = new AtomicInteger();
        private final AtomicInteger openCount = new AtomicInteger();
        private final AtomicInteger closedForwards = new AtomicInteger();

        private FakeSshConnection(List<Integer> ports) {
            this.ports = ports;
        }

        @Override
        public CommandResult executeCommand(String command) {
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
        public InputStream getRemoteFileInputStream(String remotePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutputStream getRemoteFileOutputStream(String remotePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getConnectionInfo() {
            return "fake";
        }

        @Override
        public int forwardLocalPort(int localPort, String remoteHost, int remotePort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SshLocalPortForward openLocalPortForward(int preferredLocalPort, String remoteHost, int remotePort) {
            openCount.incrementAndGet();
            int port = ports.get(portIndex.getAndIncrement());
            return new SshLocalPortForward() {
                private boolean closed;

                @Override
                public int localPort() {
                    return port;
                }

                @Override
                public String remoteHost() {
                    return remoteHost;
                }

                @Override
                public int remotePort() {
                    return remotePort;
                }

                @Override
                public void close() {
                    if (!closed) {
                        closed = true;
                        closedForwards.incrementAndGet();
                    }
                }
            };
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
