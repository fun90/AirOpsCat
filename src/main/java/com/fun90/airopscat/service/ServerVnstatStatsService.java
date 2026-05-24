package com.fun90.airopscat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerVnstatStats;
import com.fun90.airopscat.repository.ServerVnstatStatsRepository;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.service.ssh.ServerSshConfigFactory;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@ApplicationScoped
public class ServerVnstatStatsService {

    private static final String DETECT_IFACE_CMD =
            "ip route get 1.1.1.1 2>/dev/null | awk '/dev/{for(i=1;i<=NF;i++) if($i==\"dev\"){print $(i+1);exit}}'";

    @Inject
    ServerVnstatStatsRepository repository;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    ServerSshConfigFactory serverSshConfigFactory;

    @Transactional
    public void collectFromServer(Server server) {
        try (SshConnection connection = sshConnectionService.createConnection(serverSshConfigFactory.create(server, 15000))) {
            String iface = detectIface(connection, server.getId());
            String json = executeVnstat(connection, iface, server.getId());
            if (json == null) {
                return;
            }
            VnstatMonth month = parseCurrentMonth(json, server.getId());
            if (month == null) {
                return;
            }
            insert(server.getId(), iface, month);
            log.debug("vnstat 采集完成，serverId={}, iface={}, rx={}, tx={}", server.getId(), iface, month.rxBytes(), month.txBytes());
        } catch (Exception e) {
            log.warn("vnstat 采集失败，serverId={}, error={}", server.getId(), e.getMessage());
        }
    }

    public Optional<VnstatPeriodTraffic> getPeriodTraffic(Long serverId, int year, int month) {
        return repository.findLatestByServerIdAndPeriod(serverId, year, month)
                .map(s -> new VnstatPeriodTraffic(s.getRxBytes(), s.getTxBytes()));
    }

    public List<VnstatSnapshotData> getSnapshots(Long serverId, LocalDateTime since) {
        return repository.findByServerIdAndSampledAtAfter(serverId, since)
                .stream()
                .map(s -> new VnstatSnapshotData(s.getSampledAt(), s.getRxBytes(), s.getTxBytes()))
                .toList();
    }

    @Transactional
    public long deleteByServerId(Long serverId) {
        return repository.delete("serverId", serverId);
    }

    @Transactional
    public long cleanupExpiredStats(LocalDateTime cutoff) {
        return repository.deleteBySampledAtBefore(cutoff);
    }

    private String detectIface(SshConnection connection, Long serverId) {
        try {
            CommandResult result = connection.executeCommand(DETECT_IFACE_CMD);
            if (result.isSuccess() && result.getStdout() != null) {
                String iface = result.getStdout().trim();
                if (!iface.isEmpty()) {
                    return iface;
                }
            }
        } catch (Exception e) {
            log.debug("探测出口网卡失败，serverId={}, 使用默认 eth0", serverId);
        }
        return "eth0";
    }

    private String executeVnstat(SshConnection connection, String iface, Long serverId) {
        try {
            CommandResult result = connection.executeCommand("vnstat -i " + iface + " --json m 2>/dev/null");
            if (!result.isSuccess() || result.getStdout() == null || result.getStdout().isBlank()) {
                log.debug("vnstat 未安装或无数据，serverId={}", serverId);
                return null;
            }
            return result.getStdout();
        } catch (Exception e) {
            log.debug("执行 vnstat 失败，serverId={}, error={}", serverId, e.getMessage());
            return null;
        }
    }

    private VnstatMonth parseCurrentMonth(String json, Long serverId) {
        try {
            JsonNode root = JsonUtil.toObject(json, JsonNode.class);
            // jsonversion 2（vnstat 2.6+）单位为字节；jsonversion 1（旧版）单位为 KiB
            boolean isBytesFormat = "2".equals(root.path("jsonversion").asText("1"));

            JsonNode interfaces = root.path("interfaces");
            if (!interfaces.isArray() || interfaces.isEmpty()) {
                return null;
            }
            JsonNode months = interfaces.get(0).path("traffic").path("month");
            if (!months.isArray() || months.isEmpty()) {
                return null;
            }
            // vnstat 返回的最后一条是当前月
            JsonNode current = months.get(months.size() - 1);
            long rx = current.path("rx").asLong(0);
            long tx = current.path("tx").asLong(0);
            if (!isBytesFormat) {
                rx = rx * 1024L;
                tx = tx * 1024L;
            }
            return new VnstatMonth(rx, tx);
        } catch (Exception e) {
            log.warn("解析 vnstat JSON 失败，serverId={}, error={}", serverId, e.getMessage());
            return null;
        }
    }

    private void insert(Long serverId, String iface, VnstatMonth month) {
        LocalDateTime now = LocalDateTime.now();
        int year = now.getYear();
        int monthValue = now.getMonthValue();
        ServerVnstatStats stats = new ServerVnstatStats();
        stats.setServerId(serverId);
        stats.setIface(iface);
        stats.setPeriodYear((short) year);
        stats.setPeriodMonth((byte) monthValue);
        stats.setRxBytes(month.rxBytes());
        stats.setTxBytes(month.txBytes());
        stats.setSampledAt(now);
        repository.persist(stats);
    }

    public record VnstatPeriodTraffic(long rxBytes, long txBytes) {}

    public record VnstatSnapshotData(LocalDateTime sampledAt, long rxBytes, long txBytes) {}

    private record VnstatMonth(long rxBytes, long txBytes) {}
}
