package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerHost;
import com.fun90.airopscat.model.entity.ServerTrafficStats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeFailoverServiceTest {

    private static final double THRESHOLD = 0.9;

    private final List<NodeServer> candidates = List.of(placement(1L, 10L), placement(2L, 20L));

    @Test
    void picksHighestPriorityBelowThreshold() {
        assertEquals(1L, NodeFailoverService.selectTarget(candidates, Map.of(10L, 0.3, 20L, 0.1), THRESHOLD).getId());
    }

    @Test
    void skipsServerOverThreshold() {
        assertEquals(2L, NodeFailoverService.selectTarget(candidates, Map.of(10L, 0.9, 20L, 0.1), THRESHOLD).getId());
    }

    @Test
    void returnsNullWhenAllOverThreshold() {
        assertNull(NodeFailoverService.selectTarget(candidates, Map.of(10L, 0.95, 20L, 1.2), THRESHOLD));
    }

    @Test
    void switchesBackWhenNewPeriodResetsUsage() {
        // 新周期未采集到流量时使用率按 0 计算，优先级最高的服务器重新成为目标
        assertEquals(1L, NodeFailoverService.selectTarget(candidates, Map.of(20L, 0.2), THRESHOLD).getId());
    }

    @Test
    void usageIsZeroWithoutBandwidthOrStats() {
        Server server = new Server();
        ServerTrafficStats stats = new ServerTrafficStats();
        stats.setUploadBytes(1L << 40);
        assertEquals(0, NodeFailoverService.usageRatio(server, stats));
        server.setBandwidth(100);
        assertEquals(0, NodeFailoverService.usageRatio(server, null));
    }

    @Test
    void usageIsUploadPlusDownloadOverBandwidth() {
        Server server = new Server();
        server.setBandwidth(100);
        ServerTrafficStats stats = new ServerTrafficStats();
        stats.setUploadBytes(20L << 30);
        stats.setDownloadBytes(30L << 30);
        assertEquals(0.5, NodeFailoverService.usageRatio(server, stats), 1e-9);
    }

    @Test
    void dnsContentFollowsRecordType() {
        NodeServer target = placement(1L, 10L);
        target.getServer().setIp("1.2.3.4");

        assertEquals("1.2.3.4", NodeFailoverService.resolveDnsContent(record("A"), target));
        // CNAME 不能指向 IP
        assertThrows(IllegalStateException.class, () -> NodeFailoverService.resolveDnsContent(record("CNAME"), target));

        ServerHost accessHost = new ServerHost();
        accessHost.setHost("hk1.example.com");
        target.setAccessHost(accessHost);
        assertEquals("hk1.example.com", NodeFailoverService.resolveDnsContent(record("CNAME"), target));
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

    private static DomainDnsRecord record(String type) {
        DomainDnsRecord record = new DomainDnsRecord();
        record.setType(type);
        return record;
    }
}
