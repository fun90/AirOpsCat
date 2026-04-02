package com.fun90.airopscat.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerTrafficStatsServiceTest {

    @Test
    void shouldStopCleanupAfterRepositoryReturnsZero() {
        TestableServerTrafficStatsService service = new TestableServerTrafficStatsService(List.of(1000, 1000, 0));

        long deleted = service.cleanupExpiredStats();

        assertEquals(2000L, deleted);
        assertEquals(List.of(1000, 1000, 1000), service.batchInvocations);
    }

    static class TestableServerTrafficStatsService extends ServerTrafficStatsService {
        final List<Integer> deleteResults;
        final List<Integer> batchInvocations = new ArrayList<>();

        TestableServerTrafficStatsService(List<Integer> deleteResults) {
            this.deleteResults = new ArrayList<>(deleteResults);
        }

        @Override
        int getRetentionDays() {
            return 180;
        }

        @Override
        int getCleanupBatchSize() {
            return 1000;
        }

        @Override
        int deleteExpiredStatsBatch(LocalDateTime cutoffTime, int batchSize) {
            batchInvocations.add(batchSize);
            return deleteResults.removeFirst();
        }

        @Override
        void logCleanupSummary(int retentionDays, int batchSize, LocalDateTime cutoffTime, int rounds, long totalDeleted) {
        }
    }
}
