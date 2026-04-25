package com.fun90.airopscat.singbox;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshLocalPortForward;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SingBoxClashApiClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reloadConfigShouldCallClashApiConfigsEndpoint() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(SingBoxClashApiClient.LOCAL_HOST, 0), 0);
        server.createContext("/configs", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(readBody(exchange));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        FakeSystemConfigService configService = new FakeSystemConfigService(server.getAddress().getPort());
        SingBoxClashApiClient client = new SingBoxClashApiClient(configService);
        FakeSshConnection connection = new FakeSshConnection(server.getAddress().getPort());

        client.reloadConfig(connection);

        assertEquals("PUT", method.get());
        assertEquals("/configs", path.get());
        assertEquals("Bearer test-secret", authorization.get());
        assertEquals("{\"path\":\"/etc/sing-box/config.json\"}", body.get());
        assertEquals(1, connection.openCount.get());
        assertEquals(1, connection.closeCount.get());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class FakeSystemConfigService extends SystemConfigService {

        private final int port;

        private FakeSystemConfigService(int port) {
            super(null, null, null);
            this.port = port;
        }

        @Override
        public String getResolvedValue(String key) {
            return switch (key) {
                case "airopscat.sing-box.clash-api.host" -> SingBoxClashApiClient.LOCAL_HOST;
                case "airopscat.sing-box.clash-api.secret" -> "test-secret";
                default -> "";
            };
        }

        @Override
        public int getIntValue(String key, int defaultValue) {
            return switch (key) {
                case "airopscat.sing-box.clash-api.port" -> port;
                case "airopscat.sing-box.clash-api.timeout-seconds" -> 5;
                case "airopscat.sing-box.clash-api.max-retries" -> 0;
                default -> defaultValue;
            };
        }
    }

    private static final class FakeSshConnection implements SshConnection {
        private final int localPort;
        private final AtomicInteger openCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();

        private FakeSshConnection(int localPort) {
            this.localPort = localPort;
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
            return new SshLocalPortForward() {
                @Override
                public int localPort() {
                    return FakeSshConnection.this.localPort;
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
                    closeCount.incrementAndGet();
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
