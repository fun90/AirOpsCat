package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.service.AccountTrafficStatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/traffic-stats")
public class AccountTrafficStatsController {
    
    private final AccountTrafficStatsService trafficStatsService;
    
    @Autowired
    public AccountTrafficStatsController(AccountTrafficStatsService trafficStatsService) {
        this.trafficStatsService = trafficStatsService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getStatsPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        Page<AccountTrafficStats> statsPage = trafficStatsService.getStatsPage(
                page, size, search, userId, accountId, startDate, endDate);

        Map<String, Object> response = new HashMap<>();
        response.put("records", statsPage.getContent());
        response.put("total", statsPage.getTotalElements());
        response.put("pages", statsPage.getTotalPages());
        response.put("current", page);
        response.put("size", size);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountTrafficStats> getStatsById(@PathVariable Long id) {
        AccountTrafficStats stats = trafficStatsService.getStatsById(id);
        if (stats != null) {
            return ResponseEntity.ok(stats);
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> getStatsByUser(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Page<AccountTrafficStats> statsPage = trafficStatsService.getStatsPage(
                page, size, null, userId, null, null, null);
        
        Map<String, Object> response = new HashMap<>();
        response.put("records", statsPage.getContent());
        response.put("total", statsPage.getTotalElements());
        response.put("pages", statsPage.getTotalPages());
        response.put("current", page);
        response.put("size", size);
        
        // 添加总计流量数据
        response.put("totalUpload", trafficStatsService.getTotalUploadByUser(userId));
        response.put("totalDownload", trafficStatsService.getTotalDownloadByUser(userId));
        response.put("totalUploadFormatted", trafficStatsService.formatBytes(trafficStatsService.getTotalUploadByUser(userId)));
        response.put("totalDownloadFormatted", trafficStatsService.formatBytes(trafficStatsService.getTotalDownloadByUser(userId)));

        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/account/{accountId}")
    public ResponseEntity<Map<String, Object>> getStatsByAccount(
            @PathVariable Long accountId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Page<AccountTrafficStats> statsPage = trafficStatsService.getStatsPage(
                page, size, null, null, accountId, null, null);
        
        Map<String, Object> response = new HashMap<>();
        response.put("records", statsPage.getContent());
        response.put("total", statsPage.getTotalElements());
        response.put("pages", statsPage.getTotalPages());
        response.put("current", page);
        response.put("size", size);
        
        // 添加总计流量数据
        response.put("totalUpload", trafficStatsService.getTotalUploadByAccount(accountId));
        response.put("totalDownload", trafficStatsService.getTotalDownloadByAccount(accountId));
        response.put("totalUploadFormatted", trafficStatsService.formatBytes(trafficStatsService.getTotalUploadByAccount(accountId)));
        response.put("totalDownloadFormatted", trafficStatsService.formatBytes(trafficStatsService.getTotalDownloadByAccount(accountId)));

        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<AccountTrafficStats> createStats(@RequestBody AccountTrafficStats stats) {
        AccountTrafficStats savedStats = trafficStatsService.saveStats(stats);
        return ResponseEntity.ok(savedStats);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AccountTrafficStats> updateStats(@PathVariable Long id, @RequestBody AccountTrafficStats stats) {
        AccountTrafficStats existingStats = trafficStatsService.getStatsById(id);
        if (existingStats == null) {
            return ResponseEntity.notFound().build();
        }

        stats.setId(id);
        AccountTrafficStats updatedStats = trafficStatsService.updateStats(stats);
        return ResponseEntity.ok(updatedStats);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStats(@PathVariable Long id) {
        AccountTrafficStats existingStats = trafficStatsService.getStatsById(id);
        if (existingStats == null) {
            return ResponseEntity.notFound().build();
        }

        trafficStatsService.deleteStats(id);
        return ResponseEntity.ok().build();
    }
}