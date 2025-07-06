package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.ServerConfigDto;
import com.fun90.airopscat.model.dto.ServerConfigRequest;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.core.CoreManagementService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ServerConfigService {

    private final ServerConfigRepository serverConfigRepository;
    private final ServerRepository serverRepository;
    private final CoreManagementService coreManagementService;

    @Autowired
    public ServerConfigService(ServerConfigRepository serverConfigRepository, 
                              ServerRepository serverRepository,
                              CoreManagementService coreManagementService) {
        this.serverConfigRepository = serverConfigRepository;
        this.serverRepository = serverRepository;
        this.coreManagementService = coreManagementService;
    }

    /**
     * 分页查询服务器配置
     */
    public Page<ServerConfig> getServerConfigPage(int page, int size, String search, String configType) {
        // 创建分页对象，按创建时间倒序
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("createTime").descending());

        // 创建动态查询条件
        Specification<ServerConfig> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 确保查询包含服务器信息
            if (query.getResultType().equals(ServerConfig.class)) {
                root.fetch("server", JoinType.LEFT);
            }

            // 搜索条件：在服务器IP、主机名、配置类型中搜索
            if (StringUtils.hasText(search)) {
                Join<ServerConfig, Server> serverJoin = root.join("server", JoinType.LEFT);
                Predicate ipPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(serverJoin.get("ip")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate hostPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(serverJoin.get("host")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate namePredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(serverJoin.get("name")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate configTypePredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("configType")),
                        "%" + search.toLowerCase() + "%"
                );
                predicates.add(criteriaBuilder.or(ipPredicate, hostPredicate, namePredicate, configTypePredicate));
            }

            // 按配置类型筛选
            if (StringUtils.hasText(configType)) {
                predicates.add(criteriaBuilder.equal(root.get("configType"), configType));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        return serverConfigRepository.findAll(spec, pageable);
    }

    /**
     * 根据ID获取服务器配置
     */
    public ServerConfig getServerConfigById(Long id) {
        return serverConfigRepository.findById(id).orElse(null);
    }

    /**
     * 根据服务器ID和配置类型获取配置
     */
    public ServerConfig getServerConfigByServerIdAndType(Long serverId, String configType) {
        return serverConfigRepository.findByServerIdAndConfigType(serverId, configType).orElse(null);
    }

    /**
     * 根据服务器ID获取所有配置
     */
    public List<ServerConfig> getServerConfigsByServerId(Long serverId) {
        return serverConfigRepository.findByServerId(serverId);
    }

    /**
     * 保存服务器配置
     */
    @Transactional
    public ServerConfig saveServerConfig(ServerConfig serverConfig) {
        // 确保服务器存在
        if (serverConfig.getServerId() != null && !serverRepository.existsById(serverConfig.getServerId())) {
            throw new EntityNotFoundException("Server with ID " + serverConfig.getServerId() + " not found");
        }

        return serverConfigRepository.save(serverConfig);
    }

    /**
     * 更新服务器配置
     */
    @Transactional
    public ServerConfig updateServerConfig(ServerConfig serverConfig) {
        ServerConfig existingConfig = serverConfigRepository.findById(serverConfig.getId())
                .orElseThrow(() -> new EntityNotFoundException("ServerConfig not found"));

        // 复制非null属性
        BeanUtils.copyProperties(serverConfig, existingConfig, getNullPropertyNames(serverConfig));

        return serverConfigRepository.save(existingConfig);
    }

    /**
     * 删除服务器配置
     */
    @Transactional
    public void deleteServerConfig(Long id) {
        serverConfigRepository.deleteById(id);
    }

    /**
     * 上传配置到服务器
     */
    public CoreManagementResult uploadConfigToServer(Long configId) {
        ServerConfig serverConfig = getServerConfigById(configId);
        if (serverConfig == null) {
            throw new EntityNotFoundException("ServerConfig not found");
        }

        Server server = serverRepository.findById(serverConfig.getServerId())
                .orElseThrow(() -> new EntityNotFoundException("Server not found"));

        // 创建SSH配置
        SshConfig sshConfig = createSshConfig(server);

        // 上传配置
        CoreManagementResult result = coreManagementService.executeOperation(
                serverConfig.getConfigType(), 
                CoreOperation.CONFIG, 
                sshConfig, 
                serverConfig.getConfig()
        );

        if (result.isSuccess()) {
            // 重启服务
            CoreManagementResult restartResult = coreManagementService.executeOperation(
                    serverConfig.getConfigType(), 
                    CoreOperation.RESTART, 
                    sshConfig
            );
            
            if (!restartResult.isSuccess()) {
                result.setSuccess(false);
                result.setMessage("配置上传成功，但服务重启失败: " + restartResult.getMessage());
            }
        }

        return result;
    }

    /**
     * 获取配置类型选项
     */
    public List<Map<String, String>> getConfigTypeOptions() {
        List<String> configTypes = serverConfigRepository.findDistinctConfigTypes();
        return configTypes.stream()
                .map(type -> {
                    Map<String, String> option = new HashMap<>();
                    option.put("value", type);
                    option.put("label", type);
                    return option;
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取配置统计信息
     */
    public Map<String, Long> getServerConfigStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", serverConfigRepository.count());
        
        // 按配置类型统计
        List<String> configTypes = serverConfigRepository.findDistinctConfigTypes();
        for (String configType : configTypes) {
            long count = serverConfigRepository.countByConfigType(configType);
            stats.put(configType, count);
        }
        
        return stats;
    }

    /**
     * 创建SSH配置
     */
    private SshConfig createSshConfig(Server server) {
        SshConfig sshConfig = new SshConfig();
        sshConfig.setHost(server.getIp());
        sshConfig.setPort(server.getSshPort());
        sshConfig.setUsername(Objects.toString(server.getUsername(), "root"));

        String auth = server.getAuth();
        
        if ("PASSWORD".equalsIgnoreCase(server.getAuthType()) || "password".equalsIgnoreCase(server.getAuthType())) {
            sshConfig.setPassword(auth);
        } else {
            sshConfig.setPrivateKeyContent(auth);
        }

        return sshConfig;
    }

    /**
     * 获取对象中所有为null的属性名
     */
    private String[] getNullPropertyNames(Object source) {
        final org.springframework.beans.BeanWrapper src = new org.springframework.beans.BeanWrapperImpl(source);
        java.beans.PropertyDescriptor[] pds = src.getPropertyDescriptors();

        Set<String> nullNames = new HashSet<>();
        for (java.beans.PropertyDescriptor pd : pds) {
            Object srcValue = src.getPropertyValue(pd.getName());
            if (srcValue == null) {
                nullNames.add(pd.getName());
            }
        }
        return nullNames.toArray(new String[0]);
    }
} 