package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AlertState;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.AlertStateRepository;
import com.fun90.airopscat.service.guard.AccountGuardStats;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountOnlineLimitAlertServiceTest {

    @Test
    void shouldUseGuardStatsToTriggerConnectionLimitAlert() {
        Account account = account(1L, "acct-001", 1);
        FakeAlertStateRepository alertStateRepository = new FakeAlertStateRepository();
        FakeBarkService barkService = new FakeBarkService();
        AccountOnlineLimitAlertService service = new AccountOnlineLimitAlertService(
                new FakeAccountRepository(List.of(account)),
                alertStateRepository,
                barkService,
                new FakeSystemConfigService());

        Map<String, AccountGuardStats> stats = Map.of(
                "acct-001", new AccountGuardStats("acct-001", 2, 1, 1));

        service.checkAndNotifyFromGuard(stats);
        service.checkAndNotifyFromGuard(stats);

        AlertState state = alertStateRepository.states.getFirst();
        assertEquals("ACTIVE", state.getStatus());
        assertEquals(2.0, state.getLastValue());
        assertEquals(1.0, state.getThresholdValue());
        assertEquals(1, barkService.warningCount);
        assertTrue(barkService.lastWarningBody.contains("数据来源: guard 实时上报"));
    }

    @Test
    void shouldRecoverGuardAlertWhenConnectionCountDrops() {
        Account account = account(1L, "acct-001", 1);
        FakeAlertStateRepository alertStateRepository = new FakeAlertStateRepository();
        AccountOnlineLimitAlertService service = new AccountOnlineLimitAlertService(
                new FakeAccountRepository(List.of(account)),
                alertStateRepository,
                new FakeBarkService(),
                new FakeSystemConfigService());

        service.checkAndNotifyFromGuard(Map.of("acct-001", new AccountGuardStats("acct-001", 2, 1, 1)));
        service.checkAndNotifyFromGuard(Map.of("acct-001", new AccountGuardStats("acct-001", 2, 1, 1)));
        service.checkAndNotifyFromGuard(Map.of("acct-001", new AccountGuardStats("acct-001", 0, 0, 0)));

        AlertState state = alertStateRepository.states.getFirst();
        assertEquals("RECOVERED", state.getStatus());
        assertEquals(0.0, state.getLastValue());
        assertNotNull(state.getRecoveredTime());
    }

    private static Account account(Long id, String accountNo, int maxConnections) {
        Account account = new Account();
        account.setId(id);
        account.setAccountNo(accountNo);
        account.setRemark("测试账户");
        account.setMaxConnections(maxConnections);
        account.setDisabled(0);
        account.setToDate(LocalDateTime.now().plusDays(1));
        return account;
    }

    static class FakeAccountRepository extends AccountRepository {
        private final List<Account> accounts;

        FakeAccountRepository(List<Account> accounts) {
            this.accounts = accounts;
        }

        @Override
        public List<Account> findActiveConnectionLimitedAccounts(LocalDateTime now) {
            return accounts;
        }

        @Override
        public List<Account> findByAccountNos(Set<String> accountNos) {
            return accounts.stream()
                    .filter(account -> accountNos.contains(account.getAccountNo()))
                    .toList();
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
        public List<AlertState> findActiveByAlertType(String alertType) {
            return states.stream()
                    .filter(state -> alertType.equals(state.getAlertType()))
                    .filter(state -> "ACTIVE".equals(state.getStatus()))
                    .toList();
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
        int infoCount;
        String lastWarningBody;

        @Override
        public boolean sendWarningNotification(String title, String body) {
            warningCount++;
            lastWarningBody = body;
            return true;
        }

        @Override
        public boolean sendInfoNotification(String title, String body) {
            infoCount++;
            return true;
        }
    }

    static class FakeSystemConfigService extends SystemConfigService {
        final Map<String, Boolean> booleanValues = new HashMap<>();
        final Map<String, Integer> intValues = new HashMap<>();

        FakeSystemConfigService() {
            super(null, null, null);
        }

        @Override
        public boolean getBooleanValue(String key, boolean defaultValue) {
            return booleanValues.getOrDefault(key, defaultValue);
        }

        @Override
        public int getIntValue(String key, int defaultValue) {
            return intValues.getOrDefault(key, defaultValue);
        }
    }
}
