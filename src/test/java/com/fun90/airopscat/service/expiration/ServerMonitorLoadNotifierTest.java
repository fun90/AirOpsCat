package com.fun90.airopscat.service.expiration;

import com.fun90.airopscat.model.entity.AlertState;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.AlertStateRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.BarkService;
import com.fun90.airopscat.service.ServerMonitorStatsService;
import com.fun90.airopscat.service.SystemConfigService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServerMonitorLoadNotifierTest {

    @Test
    void shouldRetryCpuAlertWhenPreviousBarkSendFailed() {
        FakeAlertStateRepository alertStateRepository = new FakeAlertStateRepository();
        FakeBarkService barkService = new FakeBarkService();
        ServerMonitorLoadNotifier notifier = notifier(alertStateRepository, barkService);

        barkService.nextResult = false;
        notifier.findItems(LocalDate.of(2026, 5, 13));

        assertEquals(1, alertStateRepository.states.size());
        AlertState state = alertStateRepository.states.getFirst();
        assertEquals("ACTIVE", state.getStatus());
        assertNull(state.getLastNotifiedTime());
        assertEquals(1, barkService.warningCount);

        barkService.nextResult = true;
        notifier.findItems(LocalDate.of(2026, 5, 13));

        assertEquals(2, barkService.warningCount);
        assertNotNull(state.getLastNotifiedTime());
    }

    private static ServerMonitorLoadNotifier notifier(FakeAlertStateRepository alertStateRepository,
                                                      FakeBarkService barkService) {
        ServerMonitorLoadNotifier notifier = new ServerMonitorLoadNotifier();
        notifier.serverRepository = new FakeServerRepository();
        notifier.serverMonitorStatsService = new FakeServerMonitorStatsService();
        notifier.monitorTaskExecutor = new DirectExecutorService();
        notifier.systemConfigService = new FakeSystemConfigService();
        notifier.alertStateRepository = alertStateRepository;
        notifier.barkService = barkService;
        return notifier;
    }

    static class DirectExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    static class FakeServerRepository extends ServerRepository {
        @Override
        public List<Server> findMonitorableServers(LocalDate date) {
            Server server = new Server();
            server.setId(1L);
            server.setName("测试服务器");
            server.setIp("192.0.2.10");
            server.setBandwidth(0);
            return List.of(server);
        }
    }

    static class FakeServerMonitorStatsService extends ServerMonitorStatsService {
        @Override
        public boolean isCpuUsageHighForDuration(Long serverId, LocalDateTime referenceTime,
                                                 double threshold, int durationMinutes) {
            return true;
        }

        @Override
        public boolean isMemoryUsageHighForDuration(Long serverId, LocalDateTime referenceTime,
                                                    double threshold, int durationMinutes) {
            return false;
        }

        @Override
        public long getCurrentPeriodTotalTrafficBytes(Server server, LocalDateTime sampleTime) {
            return 0L;
        }
    }

    static class FakeAlertStateRepository extends AlertStateRepository {
        final List<AlertState> states = new ArrayList<>();

        @Override
        public Optional<AlertState> findByIdentity(String alertType, String resourceType, Long resourceId, String fingerprint) {
            return states.stream()
                    .filter(state -> alertType.equals(state.getAlertType()))
                    .filter(state -> resourceType.equals(state.getResourceType()))
                    .filter(state -> resourceId.equals(state.getResourceId()))
                    .filter(state -> fingerprint.equals(state.getFingerprint()))
                    .findFirst();
        }

        @Override
        public void persist(AlertState alertState) {
            if (alertState.getId() == null) {
                alertState.setId((long) states.size() + 1);
            }
            states.add(alertState);
        }
    }

    static class FakeBarkService extends BarkService {
        int warningCount;
        boolean nextResult = true;

        @Override
        public boolean sendWarningNotification(String title, String body) {
            warningCount++;
            return nextResult;
        }
    }

    static class FakeSystemConfigService extends SystemConfigService {
        final Map<String, Integer> intValues = new HashMap<>();
        final Map<String, Double> doubleValues = new HashMap<>();

        FakeSystemConfigService() {
            super(null, null, null);
            intValues.put("airopscat.server.monitor.max-parallel-servers", 1);
            intValues.put("airopscat.server.monitor.alert.continuous-minutes", 30);
            intValues.put("airopscat.server.monitor.alert.min-interval-minutes", 60);
            doubleValues.put("airopscat.server.monitor.alert.cpu-threshold", 0.9D);
            doubleValues.put("airopscat.server.monitor.alert.memory-threshold", 0.95D);
            doubleValues.put("airopscat.server.monitor.alert.traffic-threshold", 0.85D);
        }

        @Override
        public int getIntValue(String key, int defaultValue) {
            return intValues.getOrDefault(key, defaultValue);
        }

        @Override
        public double getDoubleValue(String key, double defaultValue) {
            return doubleValues.getOrDefault(key, defaultValue);
        }
    }
}
