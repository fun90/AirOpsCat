package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.service.AccountTrafficStatsService;
import com.fun90.airopscat.service.SubscriptionService;
import com.fun90.airopscat.service.SystemConfigService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenControllerDocsInfoTest {

    private static final String AUTH_CODE = "old-auth-code";
    private static final long GB = 1024L * 1024L * 1024L;

    @Test
    void shouldReturnUsageWithDocsInfo() {
        Account account = account(LocalDate.now().plusDays(10).atTime(12, 0));
        AccountTrafficStats stats = new AccountTrafficStats();
        stats.setUploadBytes(GB);
        stats.setDownloadBytes(2 * GB);
        stats.setPeriodEnd(LocalDateTime.of(2026, 10, 1, 0, 0));
        OpenController controller = controller(account, List.of(stats), 100L);

        Response response = controller.getDocsInfo(AUTH_CODE);

        assertEquals(200, response.getStatus());
        Map<String, Object> usage = usage(response);
        assertEquals(3 * GB, usage.get("usedBytes"));
        assertEquals(100 * GB, usage.get("totalBytes"));
        assertEquals("2026-10-01", usage.get("resetDate"));
        assertEquals(10L, usage.get("remainingDays"));
    }

    @Test
    void shouldReturnUnlimitedUsageWithoutStatsOrExpiry() {
        OpenController controller = controller(account(null), List.of(), null);

        Map<String, Object> usage = usage(controller.getDocsInfo(AUTH_CODE));

        assertEquals(0L, usage.get("usedBytes"));
        assertNull(usage.get("totalBytes"));
        assertNull(usage.get("resetDate"));
        assertNull(usage.get("expireDate"));
        assertNull(usage.get("remainingDays"));
    }

    @Test
    void shouldRejectUnknownAuthCode() {
        OpenController controller = controller(account(null), List.of(), null);

        assertEquals(400, controller.getDocsInfo("unknown").getStatus());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> usage(Response response) {
        Map<?, ?> body = assertInstanceOf(Map.class, response.getEntity());
        return (Map<String, Object>) body.get("usage");
    }

    private static Account account(LocalDateTime toDate) {
        Account account = new Account();
        account.setId(1L);
        account.setAccountNo("acct-001");
        account.setAuthCode(AUTH_CODE);
        account.setUuid("00000000-0000-0000-0000-000000000001");
        account.setRemark("测试用户");
        account.setToDate(toDate);
        return account;
    }

    private static OpenController controller(Account account, List<AccountTrafficStats> stats, Long quotaGb) {
        OpenController controller = new OpenController();
        controller.accountRepository = new FakeAccountRepository(account);
        controller.accountTrafficStatsService = new FakeTrafficStatsService(stats, quotaGb);
        controller.subscriptionService = new FakeSubscriptionService();
        controller.systemConfigService = new FakeSystemConfigService();
        return controller;
    }

    static class FakeAccountRepository extends AccountRepository {
        private final Account account;

        FakeAccountRepository(Account account) {
            this.account = account;
        }

        @Override
        public Optional<Account> findByAuthCode(String authCode) {
            return account.getAuthCode().equals(authCode) ? Optional.of(account) : Optional.empty();
        }
    }

    static class FakeTrafficStatsService extends AccountTrafficStatsService {
        private final List<AccountTrafficStats> stats;
        private final Long quotaGb;

        FakeTrafficStatsService(List<AccountTrafficStats> stats, Long quotaGb) {
            super(null, null, null, null, null);
            this.stats = stats;
            this.quotaGb = quotaGb;
        }

        @Override
        public List<AccountTrafficStats> getStatsByAccountAndCurrentTime(Long accountId, LocalDateTime currentTime) {
            return stats;
        }

        @Override
        public Long getEffectiveBandwidth(Long accountId) {
            return quotaGb;
        }
    }

    static class FakeSubscriptionService extends SubscriptionService {
        FakeSubscriptionService() {
            super(null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public String getConfigUrl(Account account, String osName, String appName) {
            return "https://example.com/config/" + account.getAuthCode() + "/" + osName + "/" + appName + "/";
        }
    }

    static class FakeSystemConfigService extends SystemConfigService {
        FakeSystemConfigService() {
            super(null, null, null);
        }

        @Override
        public String getResolvedValue(String key) {
            return null;
        }
    }
}
