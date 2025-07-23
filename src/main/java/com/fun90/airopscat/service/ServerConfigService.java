package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.core.CoreManagementService;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class ServerConfigService {

    @Inject
    ServerConfigRepository serverConfigRepository;
    
    @Inject
    ServerRepository serverRepository;
    
    @Inject
    CoreManagementService coreManagementService;

    /**
     * 分页查询服务器配置
     */
    public io.quarkus.hibernate.orm.panache.PanacheQuery<ServerConfig> getServerConfigPage(String search, String configType) {
        // Build query string
        Map<String, Object> params = new HashMap<>();
        
        List<String> conditions = new ArrayList<>();
        
        // Search condition - search in configType or related server properties
        if (search != null && !search.trim().isEmpty()) {
            conditions.add("(lower(configType) like :search or serverId in (select id from Server where lower(ip) like :search or lower(host) like :search or lower(name) like :search))");
            params.put("search", "%" + search.toLowerCase() + "%");
        }
        
        // Config type filter
        if (configType != null && !configType.trim().isEmpty()) {
            conditions.add("configType = :configType");
            params.put("configType", configType);
        }
        
        String query = conditions.isEmpty() ? "" : String.join(" and ", conditions);
        Sort sort = Sort.by("createTime").descending();
        
        if (query.isEmpty()) {
            return serverConfigRepository.findAll(sort);
        } else {
            return serverConfigRepository.find(query, sort, params);
        }
    }

    /**
     * 根据ID获取服务器配置
     */
    public ServerConfig getServerConfigById(Long id) {
        return serverConfigRepository.findById(id);
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
        if (serverConfig.getServerId() != null && serverRepository.findById(serverConfig.getServerId()) == null) {
            throw new EntityNotFoundException("Server with ID " + serverConfig.getServerId() + " not found");
        }

        serverConfigRepository.persist(serverConfig);
        return serverConfig;
    }

    /**
     * 更新服务器配置
     */
    @Transactional
    public ServerConfig updateServerConfig(ServerConfig serverConfig) {
        ServerConfig existingConfig = serverConfigRepository.findById(serverConfig.getId());
        if (existingConfig == null) {
            throw new EntityNotFoundException("ServerConfig not found");
        }

        // 复制非null属性
        copyNonNullProperties(serverConfig, existingConfig);

        // No need to call save/persist for updates in Panache
        return existingConfig;
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

        Server server = serverRepository.findById(serverConfig.getServerId());
        if (server == null) {
            throw new EntityNotFoundException("Server not found");
        }

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
     * 复制非null属性
     */
    private void copyNonNullProperties(ServerConfig source, ServerConfig target) {
        if (source.getServerId() != null) target.setServerId(source.getServerId());
        if (source.getConfigType() != null) target.setConfigType(source.getConfigType());
        if (source.getConfig() != null) target.setConfig(source.getConfig());
        if (source.getDescription() != null) target.setDescription(source.getDescription());
        if (source.getEnabled() != null) target.setEnabled(source.getEnabled());
        if (source.getUpdateTime() != null) target.setUpdateTime(source.getUpdateTime());
    }
} 