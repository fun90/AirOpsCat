package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountOnlineIpDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AlertState;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.AlertStateRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountOnlineLimitAlertServiceTest {

    @Test
    void shouldPersistAlertStateAndRespectNotifyInterval() {
        Account account = account(1L, "acct-001", 1);
        FakeAccountRepository accountRepository = new FakeAccountRepository(List.of(account));
        FakeAccountOnlineIpService onlineIpService = new FakeAccountOnlineIpService();
        FakeAlertStateRepository alertStateRepository = new FakeAlertStateRepository();
        FakeBarkService barkService = new FakeBarkService();
        FakeSystemConfigService configService = new FakeSystemConfigService();
        configService.intValues.put("airopscat.account.connection-limit.alert.min-interval-minutes", 60);

        onlineIpService.records = List.of(record("conn-1", "10.0.0.1", "node-a"), record("conn-2", "10.0.0.1", "node-a"));
        AccountOnlineLimitAlertService service = new AccountOnlineLimitAlertService(
                accountRepository, onlineIpService, alertStateRepository, barkService, configService);

        service.checkAndNotify();
        service.checkAndNotify();

        assertEquals(1, alertStateRepository.states.size());
        AlertState state = alertStateRepository.states.getFirst();
        assertEquals("ACTIVE", state.getStatus());
        assertEquals(2.0, state.getLastValue());
        assertEquals(1.0, state.getThresholdValue());
        assertEquals(2, state.getTriggerCount());
        assertNotNull(state.getLastNotifiedTime());
        assertEquals(1, barkService.warningCount);
        assertTrue(barkService.lastWarningBody.contains("当前连接数: 2"));
    }

    @Test
    void shouldRecoverActiveAlertWhenConnectionCountIsBackWithinLimit() {
        Account account = account(1L, "acct-001", 2);
        FakeAccountRepository accountRepository = new FakeAccountRepository(List.of(account));
        FakeAccountOnlineIpService onlineIpService = new FakeAccountOnlineIpService();
        FakeAlertStateRepository alertStateRepository = new FakeAlertStateRepository();
        FakeBarkService barkService = new FakeBarkService();
        FakeSystemConfigService configService = new FakeSystemConfigService();
        AccountOnlineLimitAlertService service = new AccountOnlineLimitAlertService(
                accountRepository, onlineIpService, alertStateRepository, barkService, configService);

        onlineIpService.records = List.of(
                record("conn-1", "10.0.0.1", "node-a"),
                record("conn-2", "10.0.0.2", "node-a"),
                record("conn-3", "10.0.0.3", "node-b"));
        service.checkAndNotify();

        onlineIpService.records = List.of(record("conn-1", "10.0.0.1", "node-a"));
        service.checkAndNotify();

        AlertState state = alertStateRepository.states.getFirst();
        assertEquals("RECOVERED", state.getStatus());
        assertEquals(1.0, state.getLastValue());
        assertEquals(2.0, state.getThresholdValue());
        assertNotNull(state.getRecoveredTime());
        assertEquals(1, barkService.warningCount);
        assertEquals(0, barkService.infoCount);
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

    private static AccountOnlineIpDto record(String connectionId, String clientIp, String nodeName) {
        AccountOnlineIpDto dto = new AccountOnlineIpDto();
        dto.setAccountNo("acct-001");
        dto.setConnectionId(connectionId);
        dto.setClientIp(clientIp);
        dto.setNodeIp("192.0.2.10");
        dto.setNodeName(nodeName);
        dto.setLastOnlineTime(LocalDateTime.now());
        return dto;
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
    }

    static class FakeAccountOnlineIpService extends AccountOnlineIpService {
        List<AccountOnlineIpDto> records = List.of();

        FakeAccountOnlineIpService() {
            super(null, null, null, null, null);
        }

        @Override
        public List<AccountOnlineIpDto> getOnlineRecordsByAccountNos(List<String> accountNos) {
            return records.stream()
                    .filter(record -> accountNos.contains(record.getAccountNo()))
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
