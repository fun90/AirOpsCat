package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

        task.collectForAllServers();

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
        List<ServerConfig> loadEnabledSingBoxConfigs() {
            return serverConfigs;
        }

        @Override
        Server loadServer(Long serverId) {
            return serverMap.get(serverId);
        }

        @Override
        TrafficCollectResult collectServerTrafficStats(Server server, List<ServerConfig> serverConfigs) {
            collectInvocationCount.incrementAndGet();
            lastCollectedConfigCount = serverConfigs.size();
            return new TrafficCollectResult(0, 0, 0);
        }
    }
}
