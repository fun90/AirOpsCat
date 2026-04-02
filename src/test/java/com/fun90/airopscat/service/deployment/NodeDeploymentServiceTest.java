package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.service.core.CoreManagementService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshLocalPortForward;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeDeploymentServiceTest {

    @Test
    void shouldReuseSingleConnectionWhenStoppingMultipleSourceCores() {
        TestableNodeDeploymentService service = new TestableNodeDeploymentService();
        Server server = new Server();
        server.setId(20L);
        server.setIp("127.0.0.1");
        server.setName("switch-server");
        service.servers.put(20L, server);

        service.stopSourceCoresBeforeRedeploy(Map.of(20L, Set.of("xray", "sing-box")));

        assertEquals(1, service.connectionCreateCount.get());
        assertEquals(2, service.executedCoreTypes.size());
        assertEquals(1, service.connectionIdentities.size());
    }

    @Test
    void shouldSkipExternalServerWhenStoppingSourceCores() {
        TestableNodeDeploymentService service = new TestableNodeDeploymentService();
        Server server = new Server();
        server.setId(21L);
        server.setIp("127.0.0.1");
        server.setName("external-server");
        server.setExternal(1);
        service.servers.put(21L, server);

        service.stopSourceCoresBeforeRedeploy(Map.of(21L, Set.of("xray", "sing-box")));

        assertEquals(0, service.connectionCreateCount.get());
        assertEquals(0, service.executedCoreTypes.size());
    }

    static class TestableNodeDeploymentService extends NodeDeploymentService {
        final Map<Long, Server> servers = new LinkedHashMap<>();
        final AtomicInteger connectionCreateCount = new AtomicInteger();
        final List<String> executedCoreTypes = new ArrayList<>();
        final Set<Integer> connectionIdentities = new java.util.LinkedHashSet<>();

        TestableNodeDeploymentService() {
            super(null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        Server loadServer(Long serverId) {
            return servers.get(serverId);
        }

        @Override
        SshConnection createConnection(Server server) {
            connectionCreateCount.incrementAndGet();
            return new NoopSshConnection();
        }

        @Override
        List<CoreManagementResult> executeOperations(String coreType,
                                                     SshConnection connection,
                                                     Server server,
                                                     CoreManagementService.OperationRequest... requests) {
            executedCoreTypes.add(coreType);
            connectionIdentities.add(System.identityHashCode(connection));

            List<CoreManagementResult> results = new ArrayList<>(requests.length);
            for (CoreManagementService.OperationRequest request : requests) {
                CoreManagementResult result = new CoreManagementResult();
                result.setSuccess(true);
                result.setOperation(request.operation().name());
                result.setCoreType(coreType);
                result.setServerAddress(server.getIp());
                result.setOperationTime(LocalDateTime.now());
                if (request.operation() == CoreOperation.STOP) {
                    result.setMessage("stop-success");
                }
                results.add(result);
            }
            return results;
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
        public InputStream getRemoteFileInputStream(String remotePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutputStream getRemoteFileOutputStream(String remotePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getConnectionInfo() {
            return "noop";
        }

        @Override
        public int forwardLocalPort(int localPort, String remoteHost, int remotePort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SshLocalPortForward openLocalPortForward(int preferredLocalPort, String remoteHost, int remotePort) {
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
