package com.fun90.airopscat.service.ratelimit;

import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.ServerRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitServiceTest {

    @Test
    void shouldUseRuntimeTargetQueryForFullAndSingleServerSync() {
        Server target = new Server();
        target.setId(10L);
        TestServerRepository repository = new TestServerRepository(List.of(target), 10L);
        RateLimitService service = new RateLimitService();
        service.serverRepository = repository;

        assertEquals(List.of(target), service.findRuntimeTargetServers());
        assertTrue(service.isRuntimeTargetServer(10L));
        assertFalse(service.isRuntimeTargetServer(20L));
        assertEquals(3, repository.invocationCount);
    }

    static class TestServerRepository extends ServerRepository {
        private final List<Server> targets;
        private final Long targetId;
        int invocationCount;

        TestServerRepository(List<Server> targets, Long targetId) {
            this.targets = targets;
            this.targetId = targetId;
        }

        @Override
        public List<Server> findRuntimeTargetServers(LocalDate date) {
            invocationCount++;
            return targets;
        }

        @Override
        public boolean isRuntimeTargetServer(Long serverId, LocalDate date) {
            invocationCount++;
            return targetId.equals(serverId);
        }
    }
}
