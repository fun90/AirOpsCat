package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.convert.ServerConfigConverter;
import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.ServerConfigDto;
import com.fun90.airopscat.model.dto.ServerConfigRequest;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.service.ServerConfigService;
import com.fun90.airopscat.service.ServerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/server-configs")
public class ServerConfigController {

    private final ServerConfigService serverConfigService;
    private final ServerService serverService;
    
    @Autowired
    public ServerConfigController(ServerConfigService serverConfigService, ServerService serverService) {
        this.serverConfigService = serverConfigService;
        this.serverService = serverService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getServerConfigPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String configType
    ) {
        Page<ServerConfig> configPage = serverConfigService.getServerConfigPage(page, size, search, configType);
        
        // Convert to DTOs
        List<ServerConfigDto> configDtos = configPage.getContent().stream()
                .map(ServerConfigConverter::toDto)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", configDtos);
        response.put("total", configPage.getTotalElements());
        response.put("pages", configPage.getTotalPages());
        response.put("current", page);
        response.put("size", size);
        
        // Add statistics
        response.put("stats", serverConfigService.getServerConfigStats());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ServerConfigDto> getServerConfigById(@PathVariable Long id) {
        ServerConfig serverConfig = serverConfigService.getServerConfigById(id);
        if (serverConfig != null) {
            ServerConfigDto dto = ServerConfigConverter.toDto(serverConfig);
            return ResponseEntity.ok(dto);
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/server/{serverId}")
    public ResponseEntity<List<ServerConfigDto>> getServerConfigsByServer(@PathVariable Long serverId) {
        List<ServerConfig> configs = serverConfigService.getServerConfigsByServerId(serverId);
        List<ServerConfigDto> configDtos = configs.stream()
                .map(ServerConfigConverter::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(configDtos);
    }
    
    @GetMapping("/types")
    public ResponseEntity<List<Map<String, String>>> getConfigTypes() {
        return ResponseEntity.ok(serverConfigService.getConfigTypeOptions());
    }
    
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> getServerConfigStats() {
        return ResponseEntity.ok(serverConfigService.getServerConfigStats());
    }

    @PostMapping
    public ResponseEntity<?> createServerConfig(@RequestBody ServerConfigRequest request) {
        try {
            // 创建ServerConfig实体
            ServerConfig serverConfig = new ServerConfig();
            serverConfig.setServerId(request.getServerId());
            serverConfig.setConfig(request.getConfig());
            serverConfig.setConfigType(request.getConfigType());
            serverConfig.setPath(request.getPath());

            ServerConfig savedConfig = serverConfigService.saveServerConfig(serverConfig);
            
            return ResponseEntity.ok(ServerConfigConverter.toDto(savedConfig));
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateServerConfig(@PathVariable Long id, @RequestBody ServerConfigRequest request) {
        try {
            // 创建ServerConfig实体
            ServerConfig serverConfig = new ServerConfig();
            serverConfig.setId(id);
            serverConfig.setServerId(request.getServerId());
            serverConfig.setConfig(request.getConfig());
            serverConfig.setConfigType(request.getConfigType());
            serverConfig.setPath(request.getPath());
            
            ServerConfig updatedConfig = serverConfigService.updateServerConfig(serverConfig);
            
            return ResponseEntity.ok(ServerConfigConverter.toDto(updatedConfig));
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteServerConfig(@PathVariable Long id) {
        ServerConfig existingConfig = serverConfigService.getServerConfigById(id);
        if (existingConfig == null) {
            return ResponseEntity.notFound().build();
        }

        serverConfigService.deleteServerConfig(id);
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/{id}/upload")
    public ResponseEntity<CoreManagementResult> uploadConfigToServer(@PathVariable Long id) {
        try {
            CoreManagementResult result = serverConfigService.uploadConfigToServer(id);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            CoreManagementResult errorResult = new CoreManagementResult();
            errorResult.setSuccess(false);
            errorResult.setMessage("上传失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(errorResult);
        }
    }
    
    @GetMapping("/servers")
    public ResponseEntity<List<Map<String, Object>>> getServers() {
        List<Map<String, Object>> serverOptions = serverService.getAllActiveServers().stream()
                .map(server -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("id", server.getId());
                    option.put("name", (server.getName() != null ? server.getName() : "") +
                              " (" + server.getIp() + ")");
                    option.put("ip", server.getIp());
                    option.put("host", server.getHost());
                    return option;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(serverOptions);
    }
}