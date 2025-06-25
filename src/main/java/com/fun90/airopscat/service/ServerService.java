package com.fun90.airopscat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.ServerDto;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.ServerAuthType;
import com.fun90.airopscat.repository.ServerRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.beans.PropertyDescriptor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ServerService {

    private final ServerRepository serverRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public ServerService(ServerRepository serverRepository, ObjectMapper objectMapper) {
        this.serverRepository = serverRepository;
        this.objectMapper = objectMapper;
    }

    public Page<Server> getServerPage(int page, int size, String search, String supplier, Boolean expired, Boolean disabled) {
        // Create pageable with sorting (newest first)
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("createTime").descending());

        // Create specification for dynamic filtering
        Specification<Server> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Search in multiple fields
            if (StringUtils.hasText(search)) {
                Predicate ipPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("ip")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate hostPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("host")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate namePredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate supplierPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("supplier")),
                        "%" + search.toLowerCase() + "%"
                );
                predicates.add(criteriaBuilder.or(ipPredicate, hostPredicate, namePredicate, supplierPredicate));
            }

            // Filter by supplier
            if (StringUtils.hasText(supplier)) {
                predicates.add(criteriaBuilder.equal(root.get("supplier"), supplier));
            }

            // Filter by expired status
            LocalDate now = LocalDate.now();
            if (expired != null) {
                if (expired) {
                    predicates.add(criteriaBuilder.and(
                            criteriaBuilder.isNotNull(root.get("expireDate")),
                            criteriaBuilder.lessThan(root.get("expireDate"), now)
                    ));
                } else {
                    predicates.add(criteriaBuilder.or(
                            criteriaBuilder.isNull(root.get("expireDate")),
                            criteriaBuilder.greaterThanOrEqualTo(root.get("expireDate"), now)
                    ));
                }
            }

            // Filter by disabled status
            if (disabled != null) {
                predicates.add(criteriaBuilder.equal(root.get("disabled"), disabled ? 1 : 0));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        return serverRepository.findAll(spec, pageable);
    }

    public List<Server> getAllActiveServers() {
        return serverRepository.findByDisabled(0);
    }

    public Server getServerById(Long id) {
        return serverRepository.findById(id).orElse(null);
    }

    public Optional<Server> getByIp(String ip) {
        return serverRepository.findByIp(ip);
    }

    public List<Server> getExpiringServers(int days) {
        LocalDate expiryDate = LocalDate.now().plusDays(days);
        return serverRepository.findExpiringServers(expiryDate);
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
        List<Server> allServers = serverRepository.findAll();
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
        BeanUtils.copyProperties(server, dto);
        
        // 注意：auth 字段现在通过 JPA 转换器自动解密，无需手动处理
        
        // Convert JSON strings to Map objects
        try {
            if (StringUtils.hasText(server.getTransitConfig())) {
                dto.setTransitConfig(objectMapper.readValue(server.getTransitConfig(), Map.class));
            }
            
            if (StringUtils.hasText(server.getCoreConfig())) {
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
        
        // 注意：auth 字段现在通过 JPA 转换器自动加密，无需手动处理
        
        // 如果有提供配置JSON对象，转换为JSON字符串
        try {
            if (server.getTransitConfig() == null) {
                server.setTransitConfig(null);
            } else if (!(server.getTransitConfig() instanceof String)) {
                server.setTransitConfig(objectMapper.writeValueAsString(server.getTransitConfig()));
            }
            
            if (server.getCoreConfig() == null) {
                server.setCoreConfig(null);
            } else if (!(server.getCoreConfig() instanceof String)) {
                server.setCoreConfig(objectMapper.writeValueAsString(server.getCoreConfig()));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error converting config to JSON: " + e.getMessage(), e);
        }
        
        return serverRepository.save(server);
    }

    @Transactional
    public Server updateServer(Server server) {
        Server existingServer = serverRepository.findById(server.getId())
                .orElseThrow(() -> new EntityNotFoundException("Server not found"));
        
        // 注意：auth 字段现在通过 JPA 转换器自动加密，无需手动处理
        
        // 处理配置JSON
        try {
            if (server.getTransitConfig() == null) {
                server.setTransitConfig(null);
            } else if (!(server.getTransitConfig() instanceof String)) {
                server.setTransitConfig(objectMapper.writeValueAsString(server.getTransitConfig()));
            }
            
            if (server.getCoreConfig() == null) {
                server.setCoreConfig(null);
            } else if (!(server.getCoreConfig() instanceof String)) {
                server.setCoreConfig(objectMapper.writeValueAsString(server.getCoreConfig()));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error converting config to JSON: " + e.getMessage(), e);
        }

        // 使用工具方法复制非null属性
        copyNonNullProperties(server, existingServer);

        return serverRepository.save(existingServer);
    }

    // 工具方法：复制非null属性
    private void copyNonNullProperties(Object src, Object target) {
        BeanUtils.copyProperties(src, target, getNullPropertyNames(src));
    }

    // 获取对象中所有为null的属性名
    private String[] getNullPropertyNames(Object source) {
        final BeanWrapper src = new BeanWrapperImpl(source);
        PropertyDescriptor[] pds = src.getPropertyDescriptors();

        Set<String> nullNames = new HashSet<>();
        for (PropertyDescriptor pd : pds) {
            Object srcValue = src.getPropertyValue(pd.getName());
            if (srcValue == null) {
                nullNames.add(pd.getName());
            }
        }
        return nullNames.toArray(new String[0]);
    }

    @Transactional
    public void deleteServer(Long id) {
        serverRepository.deleteById(id);
    }

    @Transactional
    public Server toggleServerStatus(Long id, boolean disabled) {
        Optional<Server> optionalServer = serverRepository.findById(id);
        if (optionalServer.isPresent()) {
            Server server = optionalServer.get();
            server.setDisabled(disabled ? 1 : 0);
            return serverRepository.save(server);
        }
        return null;
    }
    
    @Transactional
    public Server renewServer(Long id, LocalDate newExpiryDate) {
        Server server = serverRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Server not found"));
        
        server.setExpireDate(newExpiryDate);
        if (server.getDisabled() == 1) {
            server.setDisabled(0); // Reactivate server if disabled
        }
        
        return serverRepository.save(server);
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
    
    /**
     * 获取认证信息（现在直接返回明文，因为 JPA 转换器自动处理解密）
     * @param serverId 服务器ID
     * @return 认证信息
     */
    public String getAuth(Long serverId) {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new EntityNotFoundException("Server not found"));
        
        return server.getAuth();
    }
    
    /**
     * 验证认证信息
     * @param serverId 服务器ID
     * @param inputAuth 输入的认证信息
     * @return 是否匹配
     */
    public boolean verifyAuth(Long serverId, String inputAuth) {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new EntityNotFoundException("Server not found"));
        
        String serverAuth = server.getAuth();
        
        if (!StringUtils.hasText(serverAuth)) {
            return !StringUtils.hasText(inputAuth);
        }
        
        return serverAuth.equals(inputAuth);
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