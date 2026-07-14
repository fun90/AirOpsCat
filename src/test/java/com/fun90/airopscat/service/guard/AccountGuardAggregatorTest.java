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

    static class FakeAccountRepository extends AccountRepository {
        @Override
        public List<Account> list(String query, Object... params) {
            Account account = new Account();
            account.setAccountNo("acct-001");
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
