package com.fun90.airopscat.service.deployment;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeDeploymentVersionServiceTest {

    @Test
    void shouldLoopCleanupUntilRepositoryReturnsNoMoreRows() {
        TestableNodeDeploymentVersionService service = new TestableNodeDeploymentVersionService(List.of(500, 500, 120, 0));

        long deleted = service.cleanupExpiredHistory();

        assertEquals(1120L, deleted);
        assertEquals(3, service.batchInvocations.size());
        assertEquals(90, service.retentionDaysUsed);
        assertEquals(20, service.keepLatestUsed);
        assertEquals(500, service.batchSizeUsed);
    }

    static class TestableNodeDeploymentVersionService extends NodeDeploymentVersionService {
        final List<Integer> deleteResults;
        final List<Integer> batchInvocations = new ArrayList<>();
        int retentionDaysUsed;
        int keepLatestUsed;
        int batchSizeUsed;

        TestableNodeDeploymentVersionService(List<Integer> deleteResults) {
            super(null, null, null, null, null, null, null);
            this.deleteResults = new ArrayList<>(deleteResults);
        }

        @Override
        int getHistoryRetentionDays() {
            retentionDaysUsed = 90;
            return retentionDaysUsed;
        }

        @Override
        int getHistoryKeepLatestPerNode() {
            keepLatestUsed = 20;
            return keepLatestUsed;
        }

        @Override
        int getHistoryCleanupBatchSize() {
            batchSizeUsed = 500;
            return batchSizeUsed;
        }

        @Override
        int deleteExpiredHistoryBatch(LocalDateTime cutoffTime, int keepLatestPerNode, int batchSize) {
            batchInvocations.add(batchSize);
            return deleteResults.removeFirst();
        }

        @Override
        void logCleanupSummary(int retentionDays,
                               int keepLatestPerNode,
                               int batchSize,
                               LocalDateTime cutoffTime,
                               int rounds,
                               long totalDeleted) {
        }
    }
}
