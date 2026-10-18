package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.AccountNodeSubscriptionDomainBinding;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerTrafficStats;
import com.fun90.airopscat.model.enums.DnsRecordStatus;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.repository.AccountNodeSubscriptionDomainBindingRepository;
import com.fun90.airopscat.repository.DomainDnsRecordRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.NodeServerRepository;
import com.fun90.airopscat.repository.ServerTrafficStatsRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerFailoverServiceTest {

    private static final double THRESHOLD = 0.9;

    // 按优先级升序：1 号部署位在服务器 10，2 号在服务器 20，3 号在服务器 30
    private final List<NodeServer> candidates = List.of(placement(1L, 10L), placement(2L, 20L), placement(3L, 30L));

    @Test
    void staysOnHomeServerBelowThreshold() {
        assertEquals(2L, ServerFailoverService.selectTarget(candidates.get(1), candidates,
                Map.of(10L, 0.1, 20L, 0.3, 30L, 0.1), THRESHOLD).getId());
    }

    @Test
    void movesToFirstOtherServerBelowThresholdByPriority() {
        assertEquals(1L, ServerFailoverService.selectTarget(candidates.get(1), candidates,
                Map.of(10L, 0.5, 20L, 0.95, 30L, 0.1), THRESHOLD).getId());
        assertEquals(3L, ServerFailoverService.selectTarget(candidates.get(1), candidates,
                Map.of(10L, 0.95, 20L, 0.95, 30L, 0.1), THRESHOLD).getId());
    }

    @Test
    void returnsNullWhenAllOverThreshold() {
        assertNull(ServerFailoverService.selectTarget(candidates.get(0), candidates,
                Map.of(10L, 0.95, 20L, 1.2, 30L, 0.9), THRESHOLD));
    }

    @Test
    void returnsToHomeWhenNewPeriodResetsUsage() {
        // 新周期未采集到流量时使用率按 0 计算，记录重新指回归属服务器
        assertEquals(1L, ServerFailoverService.selectTarget(candidates.get(0), candidates,
                Map.of(20L, 0.2), THRESHOLD).getId());
    }

    @Test
    void usageIsZeroWithoutBandwidthOrStats() {
        Server server = new Server();
        ServerTrafficStats stats = new ServerTrafficStats();
        stats.setUploadBytes(1L << 40);
        assertEquals(0, ServerFailoverService.usageRatio(server, stats));
        server.setBandwidth(100);
        assertEquals(0, ServerFailoverService.usageRatio(server, null));
    }

    @Test
    void usageIsUploadPlusDownloadOverBandwidth() {
        Server server = new Server();
        server.setBandwidth(100);
        ServerTrafficStats stats = new ServerTrafficStats();
        stats.setUploadBytes(20L << 30);
        stats.setDownloadBytes(30L << 30);
        assertEquals(0.5, ServerFailoverService.usageRatio(server, stats), 1e-9);
    }

    // 场景：节点 1 部署在服务器 10（归属，优先级 0）和 20（备用）；记录 100 是服务器 10 上部署位的域名
    @Test
    void plan_leavesRecordOnHomeServerBelowThreshold() {
        var service = service("1.1.1.1", null, 0.3, 0.1);

        assertTrue(service.plan(THRESHOLD).switches().isEmpty());
    }

    @Test
    void plan_switchesAwayWhenHomeOverThresholdAndRemembersHome() {
        var service = service("1.1.1.1", null, 0.95, 0.1);

        var changes = service.plan(THRESHOLD).switches();

        assertEquals(1, changes.size());
        assertEquals("2.2.2.2", changes.getFirst().content());
        assertEquals("1.1.1.1", changes.getFirst().homeContent());
    }

    @Test
    void plan_switchesBackAfterSystemSwitchedRecordWhenHomeRecovers() {
        var service = service("2.2.2.2", "1.1.1.1", 0.2, 0.1);

        var changes = service.plan(THRESHOLD).switches();

        assertEquals(1, changes.size());
        assertEquals("1.1.1.1", changes.getFirst().content());
        assertNull(changes.getFirst().homeContent());
    }

    @Test
    void plan_doesNotTouchRecordManuallyPointedElsewhere() {
        // 解析指向备用服务器，但不是系统切走的：视为人工指定，即使归属服务器空闲也不改
        var service = service("2.2.2.2", null, 0.2, 0.1);

        assertTrue(service.plan(THRESHOLD).switches().isEmpty());
    }

    @Test
    void plan_skipsRecordsThatAreNotSynced() {
        var service = service("1.1.1.1", null, 0.95, 0.1);
        service.domainDnsRecordRepository = records("1.1.1.1", null, DnsRecordStatus.PENDING_UPDATE);

        assertTrue(service.plan(THRESHOLD).switches().isEmpty());
    }

    private static ServerFailoverService service(String content, String homeContent, double homeUsage, double altUsage) {
        var service = new ServerFailoverService();
        var home = placement(1L, 10L, "1.1.1.1", 0);
        var alt = placement(2L, 20L, "2.2.2.2", 1);
        home.setNodeId(1L);
        alt.setNodeId(1L);
        home.setAccessHostId(100L);
        home.setDeployed(NodeDeploymentStatus.DEPLOYED.getValue());
        alt.setDeployed(NodeDeploymentStatus.DEPLOYED.getValue());
        service.nodeServerRepository = new NodeServerRepository() {
            @Override
            public List<NodeServer> list(String query, Object... params) {
                return List.of(home);
            }

            @Override
            public List<NodeServer> findByNodeId(Long nodeId) {
                return List.of(home, alt);
            }
        };
        service.bindingRepository = new AccountNodeSubscriptionDomainBindingRepository() {
            @Override
            public List<AccountNodeSubscriptionDomainBinding> list(String query, Object... params) {
                return List.of();
            }
        };
        service.nodeRepository = new NodeRepository() {
            @Override
            public Node findById(Long id) {
                Node node = new Node();
                node.setId(id);
                node.setName("n1");
                node.setType(0);
                node.setDisabled(0);
                return node;
            }
        };
        service.domainDnsRecordRepository = records(content, homeContent, DnsRecordStatus.SYNCED);
        service.serverTrafficStatsRepository = new ServerTrafficStatsRepository() {
            @Override
            public List<ServerTrafficStats> findByServerIdsAndCurrentTime(List<Long> serverIds, LocalDateTime currentTime) {
                return List.of(stats(10L, homeUsage), stats(20L, altUsage));
            }
        };
        return service;
    }

    private static DomainDnsRecordRepository records(String content, String homeContent, DnsRecordStatus status) {
        return new DomainDnsRecordRepository() {
            @Override
            public DomainDnsRecord findById(Long id) {
                DomainDnsRecord record = new DomainDnsRecord();
                record.setId(id);
                record.setDomainId(5L);
                record.setFullName("a.example.test");
                record.setType("A");
                record.setStatus(status);
                record.setExternalRecordId("ext");
                record.setContent(content);
                record.setFailoverHomeContent(homeContent);
                return record;
            }
        };
    }

    // 套餐 100GB，按使用率折算当期流量
    private static ServerTrafficStats stats(Long serverId, double usage) {
        ServerTrafficStats stats = new ServerTrafficStats();
        stats.setServerId(serverId);
        stats.setUploadBytes((long) (usage * 100 * (1L << 30)));
        stats.setDownloadBytes(0L);
        return stats;
    }

    private static NodeServer placement(Long id, Long serverId, String ip, int priority) {
        NodeServer placement = placement(id, serverId);
        placement.getServer().setIp(ip);
        placement.getServer().setBandwidth(100);
        placement.setPriority(priority);
        return placement;
    }

    private static NodeServer placement(Long id, Long serverId) {
        NodeServer placement = new NodeServer();
        placement.setId(id);
        placement.setServerId(serverId);
        Server server = new Server();
        server.setId(serverId);
        placement.setServer(server);
        return placement;
    }
}
