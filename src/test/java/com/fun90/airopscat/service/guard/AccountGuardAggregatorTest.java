package com.fun90.airopscat.service.guard;

import com.fun90.airopscat.model.dto.guard.GuardSyncAccountReport;
import com.fun90.airopscat.model.dto.guard.GuardSyncRequest;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.service.SystemConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountGuardAggregatorTest {

    @Test
    void shouldSnapshotActualConnectionCountFromGuardReports() {
        AccountGuardAggregator aggregator = new AccountGuardAggregator(
                new FakeAccountRepository(),
                new FakeSystemConfigService());

        GuardSyncRequest request = new GuardSyncRequest();
        request.setNodeIp("192.0.2.10");
        GuardSyncAccountReport report = new GuardSyncAccountReport();
        report.setAccountNo("acct-001");
        report.setConnections(7);
        report.setIps(List.of("203.0.113.10", "203.0.113.10"));
        request.setAccounts(List.of(report));

        aggregator.reportAndEvaluateWithStats(request);

        Map<String, AccountGuardStats> stats = aggregator.snapshotStatsByAccountNos(List.of("acct-001"));

        assertEquals(7, stats.get("acct-001").getTotalConnections());
        assertEquals(1, stats.get("acct-001").getTotalIps());
    }

    @Test
    void shouldBlockOnlyAfterConsecutiveCentralEvaluations() {
        FakeAccountRepository repository = new FakeAccountRepository();
        repository.maxConnections = 5;
        AccountGuardAggregator aggregator = new AccountGuardAggregator(
                repository,
                new FakeSystemConfigService());

        AccountGuardEvaluation firstNodeReport = aggregator.reportAndEvaluateWithStats(
                request("192.0.2.10", "acct-001", 4));
        AccountGuardEvaluation secondNodeReport = aggregator.reportAndEvaluateWithStats(
                request("192.0.2.11", "acct-001", 3));

        assertTrue(firstNodeReport.getBlockedAccounts().isEmpty());
        assertTrue(secondNodeReport.getBlockedAccounts().isEmpty());

        AccountGuardEvaluation firstEvaluation = aggregator.evaluateTrackedAccounts();
        assertEquals(7, firstEvaluation.getStatsByAccountNo().get("acct-001").getTotalConnections());
        assertTrue(firstEvaluation.getBlockedAccounts().isEmpty());

        AccountGuardEvaluation reportInSamePeriod = aggregator.reportAndEvaluateWithStats(
                request("192.0.2.10", "acct-001", 4));
        assertTrue(reportInSamePeriod.getBlockedAccounts().isEmpty());

        AccountGuardEvaluation secondEvaluation = aggregator.evaluateTrackedAccounts();
        assertTrue(secondEvaluation.getBlockedAccounts().containsKey("acct-001"));

        AccountGuardEvaluation blockedResponse = aggregator.reportAndEvaluateWithStats(
                request("192.0.2.11", "acct-001", 3));
        assertTrue(blockedResponse.getBlockedAccounts().containsKey("acct-001"));

        aggregator.reportAndEvaluateWithStats(request("192.0.2.11", "acct-001", 1));
        AccountGuardEvaluation recoveredEvaluation = aggregator.evaluateTrackedAccounts();
        assertFalse(recoveredEvaluation.getBlockedAccounts().containsKey("acct-001"));
    }

    private static GuardSyncRequest request(String nodeIp, String accountNo, int connections) {
        GuardSyncRequest request = new GuardSyncRequest();
        request.setNodeIp(nodeIp);
        GuardSyncAccountReport report = new GuardSyncAccountReport();
        report.setAccountNo(accountNo);
        report.setConnections(connections);
        report.setIps(List.of("203.0.113." + connections));
        request.setAccounts(List.of(report));
        return request;
    }

    static class FakeAccountRepository extends AccountRepository {
        int maxConnections;

        @Override
        public List<Account> list(String query, Object... params) {
            Account account = new Account();
            account.setAccountNo("acct-001");
            account.setMaxConnections(maxConnections);
            return List.of(account);
        }
    }

    static class FakeSystemConfigService extends SystemConfigService {
        FakeSystemConfigService() {
            super(null, null, null);
        }

        @Override
        public int getIntValue(String key, int defaultValue) {
            return defaultValue;
        }
    }
}
