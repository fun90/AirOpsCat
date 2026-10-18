package com.fun90.airopscat.service;

import com.fun90.airopscat.repository.NodeOnlineAccountDailyStatsRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeOnlineAccountStatsCleanupTest {

    @Test
    void shouldKeepExactlyNinetyDatesIncludingTodayAndDeleteAllExpiredBatches() {
        LocalDate today = LocalDate.now();
        TestRepository repository = new TestRepository(List.of(
                today.minusDays(120), today.minusDays(100), today.minusDays(90),
                today.minusDays(89), today));
        NodeOnlineAccountStatsService service = service(repository, 90, 2);

        assertEquals(3L, service.cleanupExpiredStats());
        assertEquals(List.of(today.minusDays(89), today), repository.dates);
        assertEquals(2, repository.calls);
    }

    @Test
    void shouldStopWhenLastFullBatchIsFollowedByEmptyBatch() {
        LocalDate today = LocalDate.now();
        TestRepository repository = new TestRepository(List.of(today.minusDays(91), today.minusDays(90)));

        assertEquals(2L, service(repository, 90, 2).cleanupExpiredStats());
        assertEquals(List.of(), repository.dates);
        assertEquals(2, repository.calls);
    }

    @Test
    void shouldClampInvalidSettingsAndAlwaysKeepToday() {
        LocalDate today = LocalDate.now();
        TestRepository repository = new TestRepository(List.of(today.minusDays(1), today));

        assertEquals(1L, service(repository, 0, 0).cleanupExpiredStats());
        assertEquals(List.of(today), repository.dates);
        assertEquals(List.of(1, 1), repository.batchSizes);
    }

    private NodeOnlineAccountStatsService service(TestRepository repository, int retentionDays, int batchSize) {
        NodeOnlineAccountStatsService service = new NodeOnlineAccountStatsService();
        service.dailyStatsRepository = repository;
        service.systemConfigService = new SystemConfigService(null, null, null) {
            @Override
            public int getIntValue(String key, int defaultValue) {
                return switch (key) {
                    case "airopscat.node.online-account.stats.retention-days" -> retentionDays;
                    case "airopscat.node.online-account.stats.cleanup.batch-size" -> batchSize;
                    default -> throw new AssertionError(key);
                };
            }
        };
        return service;
    }

    private static class TestRepository extends NodeOnlineAccountDailyStatsRepository {
        final List<LocalDate> dates;
        final List<Integer> batchSizes = new ArrayList<>();
        int calls;

        TestRepository(List<LocalDate> dates) {
            this.dates = new ArrayList<>(dates);
        }

        @Override
        public int deleteByStatDateBeforeBatch(LocalDate cutoffDate, int batchSize) {
            calls++;
            batchSizes.add(batchSize);
            List<LocalDate> expired = dates.stream().filter(date -> date.isBefore(cutoffDate))
                    .sorted().limit(batchSize).toList();
            dates.removeAll(expired);
            return expired.size();
        }
    }
}
