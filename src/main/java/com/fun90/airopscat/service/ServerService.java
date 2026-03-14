package com.fun90.airopscat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.ServerDto;
import com.fun90.airopscat.model.entity.Server;
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
    private final ObjectMapper objectMapper;

    @Inject
    public ServerService(ServerRepository serverRepository, ObjectMapper objectMapper) {
        this.serverRepository = serverRepository;
        this.objectMapper = objectMapper;
    }

    public io.quarkus.hibernate.orm.panache.PanacheQuery<Server> getServerPage(String search, String supplier, Boolean expired, Boolean disabled) {
        // Create sort by createTime descending
        Sort sort = Sort.by("createTime").descending();
        
        // Build query string
        Map<String, Object> params = new HashMap<>();
        
        List<String> conditions = new ArrayList<>();
        
        // Search condition
        if (search != null && !search.trim().isEmpty()) {
            conditions.add("(lower(ip) like :search or lower(host) like :search or lower(name) like :search or lower(supplier) like :search)");
            params.put("search", "%" + search.toLowerCase() + "%");
        }
        
        // Supplier filter
        if (supplier != null && !supplier.trim().isEmpty()) {
            conditions.add("supplier = :supplier");
            params.put("supplier", supplier);
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

    public List<Server> getAllActiveServers() {
        return serverRepository.findByDisabled(0);
    }

    public Server getServerById(Long id) {
        return serverRepository.findById(id);
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
    
    public BigDecimal getTotalEffectiveServerCost() {
        // 计算考虑倍率的总成本
        List<Server> allServers = serverRepository.listAll();
        return allServers.stream()
                .map(server -> {
                    BigDecimal price = server.getPrice() != null ? server.getPrice() : BigDecimal.ZERO;
                    BigDecimal multiple = server.getMultiple() != null ? server.getMultiple() : BigDecimal.ONE;
                    return price.multiply(multiple);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public ServerDto convertToDto(Server server) {
        ServerDto dto = new ServerDto();
        
        // Copy properties manually
        dto.setId(server.getId());
        dto.setIp(server.getIp());
        dto.setHost(server.getHost());
        dto.setName(server.getName());
        dto.setSupplier(server.getSupplier());
        dto.setAuthType(server.getAuthType());
        dto.setAuth(server.getAuth());
        dto.setSshPort(server.getSshPort());
        dto.setPrice(server.getPrice());
        dto.setMultiple(server.getMultiple());
        dto.setBandwidth(server.getBandwidth());
        dto.setExpireDate(server.getExpireDate());
        dto.setBandwidthDate(server.getBandwidthDate());
        dto.setDisabled(server.getDisabled());
        dto.setExternal(server.getExternal());
        dto.setCreateTime(server.getCreateTime());
        dto.setUpdateTime(server.getUpdateTime());
        dto.setRemark(server.getRemark());
        
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
    public Server saveServer(Server server) {
        // Set default values if not provided
        if (server.getDisabled() == null) {
            server.setDisabled(0);
        }
        
        if (server.getSshPort() == null) {
            server.setSshPort(22); // 默认SSH端口
        }
        
        serverRepository.persist(server);
        return server;
    }

    @Transactional
    public Server updateServer(Server server) {
        Server existingServer = serverRepository.findById(server.getId());
        if (existingServer == null) {
            throw new EntityNotFoundException("Server not found");
        }

        // Copy non-null properties manually
        copyNonNullProperties(server, existingServer);

        // No need to call save/persist for updates in Panache
        return existingServer;
    }

    // 工具方法：复制非null属性
    private void copyNonNullProperties(Server src, Server target) {
        if (src.getIp() != null) target.setIp(src.getIp());
        if (src.getHost() != null) target.setHost(src.getHost());
        if (src.getName() != null) target.setName(src.getName());
        if (src.getSupplier() != null) target.setSupplier(src.getSupplier());
        if (src.getAuthType() != null) target.setAuthType(src.getAuthType());
        if (src.getAuth() != null) target.setAuth(src.getAuth());
        if (src.getSshPort() != null) target.setSshPort(src.getSshPort());
        if (src.getPrice() != null) target.setPrice(src.getPrice());
        if (src.getMultiple() != null) target.setMultiple(src.getMultiple());
        if (src.getBandwidth() != null) target.setBandwidth(src.getBandwidth());
        if (src.getExpireDate() != null) target.setExpireDate(src.getExpireDate());
        if (src.getBandwidthDate() != null) target.setBandwidthDate(src.getBandwidthDate());
        if (src.getDisabled() != null) target.setDisabled(src.getDisabled());
        if (src.getExternal() != null) target.setExternal(src.getExternal());
        if (src.getRemark() != null) target.setRemark(src.getRemark());
        if (src.getTransitConfig() != null) target.setTransitConfig(src.getTransitConfig());
        if (src.getCoreConfig() != null) target.setCoreConfig(src.getCoreConfig());
    }

    @Transactional
    public void deleteServer(Long id) {
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
