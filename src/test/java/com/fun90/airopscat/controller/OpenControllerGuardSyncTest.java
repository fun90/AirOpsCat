package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.guard.GuardBlockedEntry;
import com.fun90.airopscat.model.dto.guard.GuardSyncAccountReport;
import com.fun90.airopscat.model.dto.guard.GuardSyncRequest;
import com.fun90.airopscat.model.dto.guard.GuardSyncResponse;
import com.fun90.airopscat.service.AccountOnlineLimitAlertService;
import com.fun90.airopscat.service.AccountOnlineIpService;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.guard.AccountGuardAggregator;
import com.fun90.airopscat.service.guard.AccountGuardEvaluation;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OpenControllerGuardSyncTest {

    @Test
    void shouldAcceptGuardSyncWithoutOnlineConnections() {
        OpenController controller = controller(new FakeOnlineIpService(false));
        GuardSyncRequest request = new GuardSyncRequest();
        request.setNodeIp("192.0.2.10");
        request.setAccounts(List.of(report("acct-001")));

        Response response = controller.guardSync(request, "token");

        assertEquals(200, response.getStatus());
        GuardSyncResponse body = assertInstanceOf(GuardSyncResponse.class, response.getEntity());
        assertEquals(1, body.getSchemaVersion());
        assertEquals(1, body.getBlockedAccounts().size());
    }

    @Test
    void shouldReturnQuotaResponseWhenOnlineRefreshFails() {
        OpenController controller = controller(new FakeOnlineIpService(true));
        GuardSyncRequest request = new GuardSyncRequest();
        request.setNodeIp("192.0.2.10");
        request.setAccounts(List.of(report("acct-001")));
        request.setOnlineConnections(List.of());

        Response response = controller.guardSync(request, "token");

        assertEquals(200, response.getStatus());
        GuardSyncResponse body = assertInstanceOf(GuardSyncResponse.class, response.getEntity());
        assertEquals(1, body.getBlockedAccounts().size());
    }

    private static OpenController controller(AccountOnlineIpService onlineIpService) {
        OpenController controller = new OpenController();
        controller.accountGuardAggregator = new FakeAggregator();
        controller.accountOnlineIpService = onlineIpService;
        controller.accountOnlineLimitAlertService = new FakeAlertService();
        controller.systemConfigService = new FakeSystemConfigService();
        return controller;
    }

    private static GuardSyncAccountReport report(String accountNo) {
        GuardSyncAccountReport report = new GuardSyncAccountReport();
        report.setAccountNo(accountNo);
        report.setConnections(1);
        report.setIps(List.of("203.0.113.10"));
        return report;
    }

    static class FakeAggregator extends AccountGuardAggregator {
        FakeAggregator() {
            super(null, null);
        }

        @Override
        public AccountGuardEvaluation reportAndEvaluateWithStats(GuardSyncRequest request) {
            return new AccountGuardEvaluation(
                    Map.of("acct-001", new GuardBlockedEntry("connections", 2, 1)),
                    Map.of());
        }

        @Override
        public int getTtlSeconds() {
            return 15;
        }
    }

    static class FakeOnlineIpService extends AccountOnlineIpService {
        private final boolean fail;

        FakeOnlineIpService(boolean fail) {
            super(null, null, null, null, null);
            this.fail = fail;
        }

        @Override
        public int refreshFromGuardConnections(String nodeIp, java.util.List<com.fun90.airopscat.model.dto.guard.GuardOnlineConnectionReport> connections) {
            if (fail) {
                throw new IllegalStateException("boom");
            }
            return 0;
        }
    }

    static class FakeAlertService extends AccountOnlineLimitAlertService {
        FakeAlertService() {
            super(null, null, null, null, null);
        }

        @Override
        public void checkAndNotifyFromGuard(Map<String, com.fun90.airopscat.service.guard.AccountGuardStats> statsByAccountNo) {
        }
    }

    static class FakeSystemConfigService extends SystemConfigService {
        FakeSystemConfigService() {
            super(null, null, null);
        }

        @Override
        public String getResolvedValue(String key) {
            return "token";
        }
    }
}
