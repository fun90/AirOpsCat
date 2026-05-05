package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.model.entity.AlertState;
import com.fun90.airopscat.repository.AlertStateRepository;
import com.fun90.airopscat.service.ratelimit.RateLimitService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AccountTrafficOverQuotaServiceTest {

    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2026, 5, 1, 0, 0);
    private static final LocalDateTime PERIOD_END = LocalDateTime.of(2026, 6, 1, 0, 0);

    @Test
    void shouldCreateAlertWithoutChangingAccountSpeedAndRespectNotifyInterval() {
        Account account = account(1L, "acct-001", 50);
        FakeAlertStateRepository alertStateRepository = new FakeAlertStateRepository();
        FakeBarkService barkService = new FakeBarkService();
        AccountTrafficOverQuotaService service = service(alertStateRepository, barkService, 20);

        service.handle(account, stats(1L, 10L, gb(10), 0));
        service.handle(account, stats(1L, 10L, gb(11), 0));

        assertEquals(50, account.getSpeed());
        assertEquals(1, alertStateRepository.states.size());
        AlertState state = alertStateRepository.states.getFirst();
        assertEquals("ACTIVE", state.getStatus());
        assertEquals(2, state.getTriggerCount());
        assertNotNull(state.getLastNotifiedTime());
        assertEquals(1, barkService.warningCount);
    }

    @Test
    void shouldRecoverAlertWhenUsageBackWithinQuota() {
        Account account = account(1L, "acct-001", null);
        FakeAlertStateRepository alertStateRepository = new FakeAlertStateRepository();
        FakeBarkService barkService = new FakeBarkService();
        AccountTrafficOverQuotaService service = service(alertStateRepository, barkService, 20);

        service.handle(account, stats(1L, 10L, gb(10), 0));
        service.handle(account, stats(1L, 10L, gb(9), 0));

        AlertState state = alertStateRepository.states.getFirst();
        assertEquals("RECOVERED", state.getStatus());
        assertNotNull(state.getRecoveredTime());
        assertEquals(1, barkService.warningCount);
        assertEquals(null, account.getSpeed());
    }

    @Test
    void shouldKeepAcknowledgedCurrentPeriodAlertSilentAndMigrateLegacyFingerprint() {
        Account account = account(1L, "acct-001", null);
        FakeAlertStateRepository alertStateRepository = new FakeAlertStateRepository();
        AlertState legacyState = legacyState(account, "ACKNOWLEDGED", PERIOD_START.plusHours(2));
        legacyState.setAcknowledgedTime(PERIOD_START.plusHours(3));
        legacyState.setAcknowledgedBy("ops@example.com");
        legacyState.setLastNotifiedTime(PERIOD_START.plusHours(1));
        legacyState.setTriggerCount(1);
        alertStateRepository.states.add(legacyState);
        FakeBarkService barkService = new FakeBarkService();
        AccountTrafficOverQuotaService service = service(alertStateRepository, barkService, 20);

        service.handle(account, stats(1L, 10L, gb(11), 0, PERIOD_START, PERIOD_END));

        AlertState state = alertStateRepository.states.getFirst();
        assertEquals("ACKNOWLEDGED", state.getStatus());
        assertEquals("acct-001:" + PERIOD_START, state.getFingerprint());
        assertEquals(2, state.getTriggerCount());
        assertEquals(0, barkService.warningCount);
    }

    @Test
    void shouldCreateNewPeriodAlertWhenOnlyLegacyAlertBelongsToPreviousPeriod() {
        Account account = account(1L, "acct-001", null);
        FakeAlertStateRepository alertStateRepository = new FakeAlertStateRepository();
        AlertState legacyState = legacyState(account, "ACKNOWLEDGED", PERIOD_START.minusDays(1));
        legacyState.setAcknowledgedTime(PERIOD_START.minusHours(12));
        legacyState.setAcknowledgedBy("ops@example.com");
        alertStateRepository.states.add(legacyState);
        FakeBarkService barkService = new FakeBarkService();
        AccountTrafficOverQuotaService service = service(alertStateRepository, barkService, 20);

        service.handle(account, stats(1L, 10L, gb(11), 0, PERIOD_START, PERIOD_END));

        assertEquals(2, alertStateRepository.states.size());
        AlertState newState = alertStateRepository.states.get(1);
        assertEquals("ACTIVE", newState.getStatus());
        assertEquals("acct-001:" + PERIOD_START, newState.getFingerprint());
        assertEquals(1, barkService.warningCount);
    }

    @Test
    void shouldRecoverAcknowledgedCurrentPeriodAlertWhenUsageBackWithinQuota() {
        Account account = account(1L, "acct-001", null);
        FakeAlertStateRepository alertStateRepository = new FakeAlertStateRepository();
        AlertState state = legacyState(account, "ACKNOWLEDGED", PERIOD_START.plusHours(2));
        state.setFingerprint("acct-001:" + PERIOD_START);
        state.setAcknowledgedTime(PERIOD_START.plusHours(3));
        state.setAcknowledgedBy("ops@example.com");
        alertStateRepository.states.add(state);
        FakeBarkService barkService = new FakeBarkService();
        AccountTrafficOverQuotaService service = service(alertStateRepository, barkService, 20);

        service.handle(account, stats(1L, 10L, gb(9), 0, PERIOD_START, PERIOD_END));

        assertEquals("RECOVERED", state.getStatus());
        assertNotNull(state.getRecoveredTime());
        assertEquals(0, barkService.warningCount);
    }

    private static AccountTrafficOverQuotaService service(FakeAlertStateRepository alertStateRepository,
                                                          FakeBarkService barkService,
                                                          int overQuotaSpeed) {
        AccountTrafficOverQuotaService service = new AccountTrafficOverQuotaService();
        AccountTrafficLimitService limitService = new AccountTrafficLimitService();
        FakeSystemConfigService configService = new FakeSystemConfigService();
        configService.intValues.put(AccountTrafficLimitService.OVER_QUOTA_SPEED_KEY, overQuotaSpeed);
        configService.intValues.put("airopscat.account.connection-limit.alert.min-interval-minutes", 60);
        limitService.systemConfigService = configService;

        service.alertStateRepository = alertStateRepository;
        service.barkService = barkService;
        service.systemConfigService = configService;
        service.accountTrafficLimitService = limitService;
        service.rateLimitService = new FakeRateLimitService();
        return service;
    }

    private static Account account(Long id, String accountNo, Integer speed) {
        Account account = new Account();
        account.setId(id);
        account.setAccountNo(accountNo);
        account.setRemark("测试账户");
        account.setSpeed(speed);
        return account;
    }

    private static AccountTrafficStats stats(Long accountId, Long bandwidthQuota, long uploadBytes, long downloadBytes) {
        return stats(accountId, bandwidthQuota, uploadBytes, downloadBytes, PERIOD_START, PERIOD_END);
    }

    private static AccountTrafficStats stats(Long accountId, Long bandwidthQuota, long uploadBytes, long downloadBytes,
                                             LocalDateTime periodStart, LocalDateTime periodEnd) {
        AccountTrafficStats stats = new AccountTrafficStats();
        stats.setAccountId(accountId);
        stats.setBandwidthQuota(bandwidthQuota);
        stats.setUploadBytes(uploadBytes);
        stats.setDownloadBytes(downloadBytes);
        stats.setPeriodStart(periodStart);
        stats.setPeriodEnd(periodEnd);
        return stats;
    }

    private static AlertState legacyState(Account account, String status, LocalDateTime lastTriggeredTime) {
        AlertState state = new AlertState();
        state.setId(1L);
        state.setAlertType("account-traffic-over-quota");
        state.setResourceType("account");
        state.setResourceId(account.getId());
        state.setResourceKey(account.getAccountNo());
        state.setFingerprint(account.getAccountNo());
        state.setStatus(status);
        state.setSeverity("WARNING");
        state.setLastTriggeredTime(lastTriggeredTime);
        return state;
    }

    private static long gb(long value) {
        return value * 1024L * 1024L * 1024L;
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

        @Override
        public boolean sendWarningNotification(String title, String body) {
            warningCount++;
            return true;
        }
    }

    static class FakeRateLimitService extends RateLimitService {
        int syncCount;

        @Override
        public void triggerAsyncSync() {
            syncCount++;
        }
    }

    static class FakeSystemConfigService extends SystemConfigService {
        final Map<String, Integer> intValues = new HashMap<>();

        FakeSystemConfigService() {
            super(null, null, null);
        }

        @Override
        public int getIntValue(String key, int defaultValue) {
            return intValues.getOrDefault(key, defaultValue);
        }
    }
}
