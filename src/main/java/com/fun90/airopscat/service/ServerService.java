package com.fun90.airopscat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.ServerDto;
import com.fun90.airopscat.model.dto.ServerHostDto;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerHost;
import com.fun90.airopscat.repository.ServerHostRepository;
import com.fun90.airopscat.model.enums.ServerAuthType;
import com.fun90.airopscat.repository.ServerRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class ServerService {

    private final ServerRepository serverRepository;
    private final ServerHostRepository serverHostRepository;
    private final ObjectMapper objectMapper;
    private final ServerHostService serverHostService;
    private final ServerMonitorStatsService serverMonitorStatsService;
    private final ServerTrafficStatsService serverTrafficStatsService;

    @Inject
    public ServerService(ServerRepository serverRepository,
                         ServerHostRepository serverHostRepository,
                         ObjectMapper objectMapper,
                         ServerHostService serverHostService,
                         ServerMonitorStatsService serverMonitorStatsService,
                         ServerTrafficStatsService serverTrafficStatsService) {
        this.serverRepository = serverRepository;
        this.serverHostRepository = serverHostRepository;
        this.objectMapper = objectMapper;
        this.serverHostService = serverHostService;
        this.serverMonitorStatsService = serverMonitorStatsService;
        this.serverTrafficStatsService = serverTrafficStatsService;
    }

    public io.quarkus.hibernate.orm.panache.PanacheQuery<Server> getServerPage(String search, String supplier, Boolean expired, Boolean disabled) {
        // Create sort by createTime descending
        Sort sort = Sort.by("createTime").descending();

        // Build query string
        Map<String, Object> params = new HashMap<>();

        List<String> conditions = new ArrayList<>();

        // Search condition
        if (search != null && !search.trim().isEmpty()) {
            appendSearchConditions(conditions, params, search);
        }

        // Supplier filter
        if (supplier != null && !supplier.trim().isEmpty()) {
            if ("__UNKNOWN__".equals(supplier)) {
                conditions.add("(supplier is null or trim(supplier) = '')");
            } else {
                conditions.add("supplier = :supplier");
                params.put("supplier", supplier);
            }
        }

        // Expired filter
        if (expired != null) {
            LocalDate now = LocalDate.now();
            if (expired) {
                conditions.add("expireDate is not null and expireDate < :now");
                params.put("now", now);
            } else {
                conditions.add("(expireDate is null or expireDate >= :now)");
                params.put("now", now);
            }
        }

        // Disabled filter
        if (disabled != null) {
            conditions.add("disabled = :disabled");
            params.put("disabled", disabled ? 1 : 0);
        }

        String query = conditions.isEmpty() ? "" : String.join(" and ", conditions);

        if (query.isEmpty()) {
            return serverRepository.findAll(sort);
        } else {
            return serverRepository.find(query, sort, params);
        }
    }

    private void appendSearchConditions(List<String> conditions, Map<String, Object> params, String search) {
        String keyword = normalizeKeyword(search);
        if (keyword == null) {
            return;
        }

        List<String> searchConditions = new ArrayList<>();
        searchConditions.add("ip = :searchExact");
        searchConditions.add("host = :searchExact");
        searchConditions.add("name = :searchExact");
        searchConditions.add("supplier = :searchExact");
        searchConditions.add("ip like :searchPrefix");
        searchConditions.add("host like :searchPrefix");
        searchConditions.add("name like :searchPrefix");
        searchConditions.add("supplier like :searchPrefix");
        searchConditions.add("name like :searchContains");
        searchConditions.add("supplier like :searchContains");

        List<Long> matchedHostServerIds = serverHostRepository.findServerIdsByKeyword(keyword, 200);
        if (!matchedHostServerIds.isEmpty()) {
            searchConditions.add("id in :matchedHostServerIds");
            params.put("matchedHostServerIds", matchedHostServerIds);
        }

        conditions.add("(" + String.join(" or ", searchConditions) + ")");
        params.put("searchExact", keyword);
        params.put("searchPrefix", keyword + "%");
        params.put("searchContains", "%" + keyword + "%");
    }

    private String normalizeKeyword(String search) {
        if (search == null) {
            return null;
        }
        String keyword = search.trim();
        return keyword.isEmpty() ? null : keyword.toLowerCase(Locale.ROOT);
    }

    public List<Server> getAllActiveServers() {
        return serverRepository.findByDisabled(0);
    }

    public Server getServerById(Long id) {
        return serverRepository.findById(id);
    }

    public String getServerHostById(Long id) {
        if (id == null) {
            return null;
        }
        Server server = serverRepository.findById(id);
        String host = serverHostService.resolvePrimaryHost(server);
        if (host == null || host.isBlank()) {
            return null;
        }
        return host.trim();
    }

    public Map<String, Long> getServersStats() {
        LocalDate now = LocalDate.now();
        LocalDate inOneMonth = now.plusMonths(1);

        Map<String, Long> stats = new HashMap<>();
        stats.put("total", serverRepository.count());
        stats.put("active", serverRepository.countActiveServers(now));
        stats.put("expired", serverRepository.countExpiredServers(now));
        stats.put("disabled", serverRepository.countDisabledServers());
        stats.put("expiringSoon", serverRepository.countExpiringInOneMonth(now, inOneMonth));

        return stats;
    }

    public Map<String, Long> getServersBySupplier() {
        List<Object[]> supplierCounts = serverRepository.countBySupplier();
        Map<String, Long> result = new HashMap<>();

        for (Object[] row : supplierCounts) {
            String supplier = (String) row[0];
            if (supplier == null || supplier.trim().isEmpty()) {
                supplier = "未知";
            }
            Long count = ((Number) row[1]).longValue();
            result.put(supplier, count);
        }

        return result;
    }

    public BigDecimal getTotalServerCost() {
        BigDecimal total = serverRepository.getTotalServerCost();
        return total != null ? total : BigDecimal.ZERO;
    }

    public ServerDto convertToDto(Server server) {
        ServerDto dto = new ServerDto();

        // Copy properties manually
        dto.setId(server.getId());
        dto.setIp(server.getIp());
        dto.setUsername(server.getUsername());
        String primaryHost = serverHostService.resolvePrimaryHost(server);
        dto.setHost(primaryHost);
        dto.setPrimaryHost(primaryHost);
        dto.setName(server.getName());
        dto.setSupplier(server.getSupplier());
        dto.setAuthType(server.getAuthType());
        dto.setAuth(server.getAuth());
        dto.setSshPort(server.getSshPort());
        dto.setPrice(server.getPrice());
        dto.setMultiple(server.getMultiple());
        dto.setBandwidth(server.getBandwidth());
        dto.setCpuCores(server.getCpuCores());
        dto.setExpireDate(server.getExpireDate());
        dto.setBandwidthDate(server.getBandwidthDate());
        dto.setDisabled(server.getDisabled());
        dto.setExternal(server.getExternal());
        dto.setCreateTime(server.getCreateTime());
        dto.setUpdateTime(server.getUpdateTime());
        dto.setRemark(server.getRemark());
        List<ServerHost> serverHosts = serverHostService.getHostsByServerId(server.getId());
        dto.setHosts(serverHostService.toDtos(serverHosts, server));

        // Convert JSON strings to Map objects
        try {
            if (server.getTransitConfig() != null && !server.getTransitConfig().trim().isEmpty()) {
                dto.setTransitConfig(objectMapper.readValue(server.getTransitConfig(), Map.class));
            }

            if (server.getCoreConfig() != null && !server.getCoreConfig().trim().isEmpty()) {
                dto.setCoreConfig(objectMapper.readValue(server.getCoreConfig(), Map.class));
            }
        } catch (JsonProcessingException e) {
            // Log the error but continue
            System.err.println("Error parsing JSON config: " + e.getMessage());
        }

        return dto;
    }

    @Transactional
    public Server saveServer(ServerDto serverDto) {
        Server server = fromDto(serverDto);
        // Set default values if not provided
        if (server.getDisabled() == null) {
            server.setDisabled(0);
        }

        if (server.getSshPort() == null) {
            server.setSshPort(22); // 默认SSH端口
        }

        serverRepository.persist(server);
        serverHostService.syncHosts(server, serverDto.getHosts(), serverDto.getHost());
        return server;
    }

    @Transactional
    public Server updateServer(ServerDto serverDto) {
        Server server = fromDto(serverDto);
        Server existingServer = serverRepository.findById(server.getId());
        if (existingServer == null) {
            throw new EntityNotFoundException("Server not found");
        }

        // Allow optional fields to be cleared during edit operations.
        copyNonNullProperties(server, existingServer);
        serverHostService.syncHosts(existingServer, serverDto.getHosts(), serverDto.getHost());

        // No need to call save/persist for updates in Panache
        return existingServer;
    }

    // 工具方法：复制非null属性
    private void copyNonNullProperties(Server src, Server target) {
        if (src.getIp() != null) target.setIp(src.getIp());
        if (src.getUsername() != null) target.setUsername(src.getUsername());
        if (src.getAuthType() != null) target.setAuthType(src.getAuthType());
        if (src.getAuth() != null) target.setAuth(src.getAuth());
        if (src.getSshPort() != null) target.setSshPort(src.getSshPort());
        if (src.getMultiple() != null) target.setMultiple(src.getMultiple());
        if (src.getCpuCores() != null) target.setCpuCores(src.getCpuCores());
        if (src.getDisabled() != null) target.setDisabled(src.getDisabled());
        if (src.getExternal() != null) target.setExternal(src.getExternal());
        target.setHost(src.getHost());
        target.setName(src.getName());
        target.setSupplier(src.getSupplier());
        target.setPrice(src.getPrice());
        target.setBandwidth(src.getBandwidth());
        target.setExpireDate(src.getExpireDate());
        target.setBandwidthDate(src.getBandwidthDate());
        target.setRemark(src.getRemark());
        target.setTransitConfig(src.getTransitConfig());
        target.setCoreConfig(src.getCoreConfig());
    }

    private Server fromDto(ServerDto dto) {
        Server server = new Server();
        server.setId(dto.getId());
        server.setIp(dto.getIp());
        server.setUsername(dto.getUsername());
        server.setAuthType(dto.getAuthType());
        server.setAuth(dto.getAuth());
        server.setSshPort(dto.getSshPort());
        server.setHost(resolvePrimaryHost(dto));
        server.setName(dto.getName());
        server.setSupplier(dto.getSupplier());
        server.setPrice(dto.getPrice());
        server.setMultiple(dto.getMultiple());
        server.setBandwidth(dto.getBandwidth());
        server.setCpuCores(dto.getCpuCores());
        server.setExpireDate(dto.getExpireDate());
        server.setBandwidthDate(dto.getBandwidthDate());
        server.setDisabled(dto.getDisabled());
        server.setExternal(dto.getExternal());
        server.setRemark(dto.getRemark());
        server.setTransitConfig(writeJson(dto.getTransitConfig()));
        server.setCoreConfig(writeJson(dto.getCoreConfig()));
        return server;
    }

    private String resolvePrimaryHost(ServerDto dto) {
        if (dto.getHosts() != null) {
            for (ServerHostDto hostDto : dto.getHosts()) {
                if (hostDto != null && hostDto.getHost() != null && !hostDto.getHost().isBlank()) {
                    return hostDto.getHost().trim();
                }
            }
        }
        if (dto.getPrimaryHost() != null && !dto.getPrimaryHost().isBlank()) {
            return dto.getPrimaryHost().trim();
        }
        if (dto.getHost() != null && !dto.getHost().isBlank()) {
            return dto.getHost().trim();
        }
        return null;
    }

    private String writeJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON config: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void deleteServer(Long id) {
        serverMonitorStatsService.deleteByServerId(id);
        serverTrafficStatsService.deleteByServerId(id);
        serverRepository.deleteById(id);
    }

    @Transactional
    public Server toggleServerStatus(Long id, boolean disabled) {
        Server server = serverRepository.findById(id);
        if (server != null) {
            server.setDisabled(disabled ? 1 : 0);
            // No need to call save/persist for updates in Panache
            return server;
        }
        return null;
    }

    @Transactional
    public Server renewServer(Long id, LocalDate newExpiryDate) {
        Server server = serverRepository.findById(id);
        if (server == null) {
            throw new EntityNotFoundException("Server not found");
        }

        server.setExpireDate(newExpiryDate);
        if (server.getDisabled() == 1) {
            server.setDisabled(0); // Reactivate server if disabled
        }

        // No need to call save/persist for updates in Panache
        return server;
    }

    public List<Map<String, String>> getAuthTypeOptions() {
        return Arrays.stream(ServerAuthType.values())
                .map(type -> {
                    Map<String, String> option = new HashMap<>();
                    option.put("value", type.name());
                    option.put("label", type.getDescription());
                    return option;
                })
                .collect(Collectors.toList());
    }

    // 测试服务器连接
    public boolean testConnection(ServerDto server) {
        // 在实际应用中，这里会有一段代码来测试SSH连接
        // 为了演示，我们只返回一个模拟的结果
        try {
            // 模拟连接延迟
            Thread.sleep(1000);

            // 简单的模拟逻辑，返回true表示连接成功
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
