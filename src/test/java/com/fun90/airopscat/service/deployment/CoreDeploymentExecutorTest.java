package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.deployment.CoreDeploymentExecution;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.entity.Node;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreDeploymentExecutorTest {

    @Test
    void shouldReuseSingleConnectionForMultipleCoreDeployments() throws Exception {
        TestableCoreDeploymentExecutor executor = new TestableCoreDeploymentExecutor(true);
        DeploymentServerContext context = createContext();

        List<CoreDeploymentExecution> results = executor.executeForServer(context);

        assertEquals(1, executor.connectionCreateCount.get());
        assertEquals(2, executor.executedCoreTypes.size());
        assertEquals(1, executor.connectionIdentities.size());
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(CoreDeploymentExecution::success));
    }

    @Test
    void shouldSkipConnectionCreationWhenRemoteDeployIsDisabled() {
        TestableCoreDeploymentExecutor executor = new TestableCoreDeploymentExecutor(false);

        List<CoreDeploymentExecution> results = executor.executeForServer(createContext());

        assertEquals(0, executor.connectionCreateCount.get());
        assertEquals(0, executor.executedCoreTypes.size());
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(CoreDeploymentExecution::success));
    }

    private DeploymentServerContext createContext() {
        Server server = new Server();
        server.setId(10L);
        server.setName("deploy-server");
        server.setIp("127.0.0.1");
        server.setSshPort(22);

        Node xrayNode = new Node();
        xrayNode.setId(1L);
        xrayNode.setServerId(10L);
        xrayNode.setCoreType("xray");

        Node singBoxNode = new Node();
        singBoxNode.setId(2L);
        singBoxNode.setServerId(10L);
        singBoxNode.setCoreType("sing-box");

        return new DeploymentServerContext(server, List.of(xrayNode, singBoxNode), null, java.util.Map.of());
    }

    static class TestableCoreDeploymentExecutor extends CoreDeploymentExecutor {
        final boolean shouldDeployRemotely;
        final AtomicInteger connectionCreateCount = new AtomicInteger();
        final List<String> executedCoreTypes = new ArrayList<>();
        final Set<Integer> connectionIdentities = new java.util.LinkedHashSet<>();

        TestableCoreDeploymentExecutor(boolean shouldDeployRemotely) {
            super(null, null, null, null, null, null);
            this.shouldDeployRemotely = shouldDeployRemotely;
        }

        @Override
        String buildConfig(DeploymentServerContext ctx, String coreType, List<Node> nodes) {
            return "config-" + coreType;
        }

        @Override
        boolean shouldDeployRemotely(Server server) {
            return shouldDeployRemotely;
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
                if (!results.isEmpty() && results.getLast().getOperation().equals(CoreOperation.CONFIG.name())) {
                    result.setMessage("restart-success");
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
