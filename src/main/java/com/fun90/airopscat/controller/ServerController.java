package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.ServerDto;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.service.ServerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/servers")
public class ServerController {
    
    private final ServerService serverService;
    
    @Autowired
    public ServerController(ServerService serverService) {
        this.serverService = serverService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getServerPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String supplier,
            @RequestParam(required = false) Boolean expired,
            @RequestParam(required = false) Boolean disabled
    ) {
        Page<Server> serverPage = serverService.getServerPage(page, size, search, supplier, expired, disabled);
        
        // Convert to DTOs
        List<ServerDto> serverDtos = serverPage.getContent().stream()
                .map(server -> serverService.convertToDto(server))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", serverDtos);
        response.put("total", serverPage.getTotalElements());
        response.put("pages", serverPage.getTotalPages());
        response.put("current", page);
        response.put("size", size);
        
        // Add statistics
        response.put("stats", serverService.getServersStats());
        response.put("supplierStats", serverService.getServersBySupplier());
        response.put("totalCost", serverService.getTotalServerCost());
        response.put("totalEffectiveCost", serverService.getTotalEffectiveServerCost());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ServerDto> getServerById(@PathVariable Long id) {
        Server server = serverService.getServerById(id);
        if (server != null) {
            ServerDto dto = serverService.convertToDto(server);
            return ResponseEntity.ok(dto);
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/suppliers")
    public ResponseEntity<Map<String, Long>> getServersBySupplier() {
        return ResponseEntity.ok(serverService.getServersBySupplier());
    }
    
    @GetMapping("/auth-types")
    public ResponseEntity<List<Map<String, String>>> getAuthTypes() {
        return ResponseEntity.ok(serverService.getAuthTypeOptions());
    }
    
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getServersStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.putAll(serverService.getServersStats());
        stats.put("supplierStats", serverService.getServersBySupplier());
        stats.put("totalCost", serverService.getTotalServerCost());
        stats.put("totalEffectiveCost", serverService.getTotalEffectiveServerCost());
        return ResponseEntity.ok(stats);
    }
    
    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody ServerDto server) {
        boolean success = serverService.testConnection(server);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "连接成功" : "连接失败");
        
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<Server> createServer(@RequestBody Server server) {
        Server savedServer = serverService.saveServer(server);
        return ResponseEntity.ok(savedServer);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ServerDto> updateServer(@PathVariable Long id, @RequestBody Server server) {
        Server existingServer = serverService.getServerById(id);
        if (existingServer == null) {
            return ResponseEntity.notFound().build();
        }

        server.setId(id);
        Server updatedServer = serverService.updateServer(server);
        ServerDto dto = serverService.convertToDto(updatedServer);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteServer(@PathVariable Long id) {
        Server existingServer = serverService.getServerById(id);
        if (existingServer == null) {
            return ResponseEntity.notFound().build();
        }

        serverService.deleteServer(id);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/enable")
    public ResponseEntity<Map<String, Object>> enableServer(@PathVariable Long id) {
        Server server = serverService.toggleServerStatus(id, false);
        if (server != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 0);
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }

    @PatchMapping("/{id}/disable")
    public ResponseEntity<Map<String, Object>> disableServer(@PathVariable Long id) {
        Server server = serverService.toggleServerStatus(id, true);
        if (server != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 1);
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }
    
    @PatchMapping("/{id}/renew")
    public ResponseEntity<ServerDto> renewServer(
            @PathVariable Long id, 
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate
    ) {
        Server server = serverService.renewServer(id, expiryDate);
        ServerDto dto = serverService.convertToDto(server);
        return ResponseEntity.ok(dto);
    }
}