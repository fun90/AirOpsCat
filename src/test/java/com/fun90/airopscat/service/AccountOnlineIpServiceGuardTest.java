package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.guard.GuardOnlineAccountIpReport;
import com.fun90.airopscat.model.dto.guard.GuardOnlineConnectionRefReport;
import com.fun90.airopscat.model.entity.AccountOnlineIp;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.repository.AccountOnlineIpRepository;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountOnlineIpServiceGuardTest {

    @Test
    void shouldRefreshOnlineRecordsFromGuardAccountIpsAndMapNode() {
        FakeAccountOnlineIpRepository onlineIpRepository = new FakeAccountOnlineIpRepository();
        AccountOnlineIpService service = new AccountOnlineIpService(
                onlineIpRepository,
                new FakeAccountRepository(),
                new FakeNodeRepository(),
                new UserRepository(),
                new FakeSystemConfigService());

        GuardOnlineAccountIpReport valid = accountIpReport("acct-001", "node_7", List.of("203.0.113.10", "203.0.113.10", ""));
        GuardOnlineAccountIpReport invalidAccount = accountIpReport("missing", "node_7", List.of("203.0.113.11"));

        int count = service.refreshFromGuardAccountIps("192.0.2.10", List.of(valid, invalidAccount));

        assertEquals(1, count);
        assertEquals(1, onlineIpRepository.records.size());
        AccountOnlineIp record = onlineIpRepository.records.getFirst();
        assertEquals("acct-001", record.getAccountNo());
        assertEquals("203.0.113.10", record.getClientIp());
        assertEquals("guard-ip|acct-001|203.0.113.10|192.0.2.10|node_7", record.getConnectionId());
        assertEquals("192.0.2.10", record.getNodeIp());
        assertEquals(7L, record.getNodeId());
        assertEquals("node_7", record.getNodeTag());
    }

    @Test
    void shouldUseConnectionRefsWhenProvided() {
        FakeAccountOnlineIpRepository onlineIpRepository = new FakeAccountOnlineIpRepository();
        AccountOnlineIpService service = new AccountOnlineIpService(
                onlineIpRepository,
                new FakeAccountRepository(),
                new FakeNodeRepository(),
                new UserRepository(),
                new FakeSystemConfigService());

        GuardOnlineAccountIpReport report = accountIpReport("acct-001", "node_7", List.of("203.0.113.10"));
        report.setConnections(List.of(connectionRef("203.0.113.10", "conn-1", "2026-07-14T10:00:00+08:00")));

        int count = service.refreshFromGuardAccountIps("192.0.2.10", List.of(report));

        assertEquals(1, count);
        AccountOnlineIp record = onlineIpRepository.records.getFirst();
        assertEquals("conn-1", record.getConnectionId());
        assertEquals(LocalDateTime.of(2026, 7, 14, 10, 0), record.getSessionStartTime());
    }

    private static GuardOnlineAccountIpReport accountIpReport(String accountNo,
                                                              String nodeTag,
                                                              List<String> clientIps) {
        GuardOnlineAccountIpReport report = new GuardOnlineAccountIpReport();
        report.setAccountNo(accountNo);
        report.setNodeTag(nodeTag);
        report.setClientIps(clientIps);
        return report;
    }

    private static GuardOnlineConnectionRefReport connectionRef(String clientIp,
                                                                String connectionId,
                                                                String start) {
        GuardOnlineConnectionRefReport report = new GuardOnlineConnectionRefReport();
        report.setClientIp(clientIp);
        report.setConnectionId(connectionId);
        report.setStart(start);
        return report;
    }

    static class FakeAccountOnlineIpRepository extends AccountOnlineIpRepository {
        final List<AccountOnlineIp> records = new ArrayList<>();

        @Override
        public void upsertOnlineStatus(String accountNo,
                                       String clientIp,
                                       String connectionId,
                                       String nodeIp,
                                       Long nodeId,
                                       String nodeTag,
                                       LocalDateTime lastOnlineTime,
                                       LocalDateTime sessionStartTime,
                                       LocalDateTime createTime,
                                       LocalDateTime updateTime,
                                       LocalDateTime offlineThresholdTime) {
            AccountOnlineIp record = new AccountOnlineIp();
            record.setAccountNo(accountNo);
            record.setClientIp(clientIp);
            record.setConnectionId(connectionId);
            record.setNodeIp(nodeIp);
            record.setNodeId(nodeId);
            record.setNodeTag(nodeTag);
            record.setLastOnlineTime(lastOnlineTime);
            record.setSessionStartTime(sessionStartTime);
            record.setCreateTime(createTime);
            record.setUpdateTime(updateTime);
            records.add(record);
        }
    }

    static class FakeAccountRepository extends AccountRepository {
        @Override
        public List<String> findExistingAccountNos(List<String> accountNos) {
            return accountNos.stream().filter("acct-001"::equals).toList();
        }
    }

    static class FakeNodeRepository extends NodeRepository {
        @Override
        public List<Node> findOnlineTrackableByServerIp(String serverIp) {
            Node node = new Node();
            node.setId(7L);
            return List.of(node);
        }
    }

    static class FakeSystemConfigService extends SystemConfigService {
        FakeSystemConfigService() {
            super(null, null, null);
        }

        @Override
        public int getIntValue(String key, int defaultValue) {
            return switch (key) {
                case "airopscat.online.check-minutes" -> 10;
                default -> defaultValue;
            };
        }
    }
}
