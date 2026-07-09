package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.ServerConnectionTestResult;
import com.fun90.airopscat.model.dto.ServerDto;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.service.ssh.ServerSshConfigFactory;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.service.ssh.SshLocalPortForward;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerServiceConnectionTest {

    @Test
    void testConnectionReturnsSuccessForSuccessfulCommand() {
        ServerService service = newService(new FakeSshConnectionService(successConnection()));

        ServerConnectionTestResult result = service.testConnection(passwordServer("secret"));

        assertTrue(result.isSuccess());
    }

    @Test
    void testConnectionReturnsFailureForCommandFailure() {
        ServerService service = newService(new FakeSshConnectionService(commandFailureConnection()));

        ServerConnectionTestResult result = service.testConnection(passwordServer("secret"));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("连接失败"));
    }

    @Test
    void testConnectionDoesNotExposeAuthContent() {
        ServerService service = newService(new FakeSshConnectionService(config -> {
            throw new RuntimeException("认证失败");
        }));

        ServerConnectionTestResult result = service.testConnection(passwordServer("very-secret-password"));

        assertFalse(result.isSuccess());
        assertFalse(result.getMessage().contains("very-secret-password"));
    }

    private ServerService newService(SshConnectionService sshConnectionService) {
        return new ServerService(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                sshConnectionService,
                new ServerSshConfigFactory()
        );
    }

    private ServerDto passwordServer(String auth) {
        ServerDto server = new ServerDto();
        server.setIp("192.0.2.20");
        server.setSshPort(22);
        server.setUsername("root");
        server.setAuthType("PASSWORD");
        server.setAuth(auth);
        return server;
    }

    private static SshConnection successConnection() {
        return (CommandOnlySshConnection) command -> {
            CommandResult result = new CommandResult();
            result.setExitStatus(0);
            result.setStdout("airopscat-ssh-ok\n");
            result.setStderr("");
            return result;
        };
    }

    private static SshConnection commandFailureConnection() {
        return (CommandOnlySshConnection) command -> {
            CommandResult result = new CommandResult();
            result.setExitStatus(1);
            result.setStdout("");
            result.setStderr("permission denied");
            return result;
        };
    }

    private static class FakeSshConnectionService extends SshConnectionService {
        private final ConnectionFactory factory;

        private FakeSshConnectionService(SshConnection connection) {
            this(config -> connection);
        }

        private FakeSshConnectionService(ConnectionFactory factory) {
            this.factory = factory;
        }

        @Override
        public SshConnection createConnection(SshConfig config) {
            return factory.create(config);
        }
    }

    @FunctionalInterface
    private interface ConnectionFactory {
        SshConnection create(SshConfig config);
    }

    private interface CommandOnlySshConnection extends SshConnection {
        @Override
        default String readRemoteFile(String remotePath) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        default void writeRemoteFile(String remotePath, String content) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        default InputStream getRemoteFileInputStream(String remotePath) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        default OutputStream getRemoteFileOutputStream(String remotePath) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        default String getConnectionInfo() {
            return "fake";
        }

        @Override
        default int forwardLocalPort(int localPort, String remoteHost, int remotePort) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        default SshLocalPortForward openLocalPortForward(int preferredLocalPort, String remoteHost, int remotePort) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        default void cancelLocalPortForward(int localPort) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        default void close() {
        }
    }
}
