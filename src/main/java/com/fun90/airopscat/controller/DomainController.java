package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.DomainDto;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.service.DomainService;
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
@RequestMapping("/api/admin/domains")
public class DomainController {
    
    private final DomainService domainService;
    
    @Autowired
    public DomainController(DomainService domainService) {
        this.domainService = domainService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getDomainPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryTo
    ) {
        Page<Domain> domainPage = domainService.getDomainPage(page, size, search, expiryFrom, expiryTo);
        
        // Convert to DTOs and add expiration info
        List<DomainDto> domainDtos = domainPage.getContent().stream()
                .map(domain -> domainService.convertToDto(domain))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", domainDtos);
        response.put("total", domainPage.getTotalElements());
        response.put("pages", domainPage.getTotalPages());
        response.put("current", page);
        response.put("size", size);
        
        // Add statistics
        response.put("expiredCount", domainService.countExpiredDomains());
        response.put("expiringCount", domainService.countExpiringInOneMonth());
        response.put("totalCost", domainService.getTotalDomainCost());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Domain> getDomainById(@PathVariable Long id) {
        Domain domain = domainService.getDomainById(id);
        if (domain != null) {
            return ResponseEntity.ok(domain);
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/expiring")
    public ResponseEntity<List<DomainDto>> getExpiringDomains(
            @RequestParam(defaultValue = "30") int days
    ) {
        List<Domain> domains = domainService.getExpiringDomains(days);
        List<DomainDto> domainDtos = domains.stream()
                .map(domain -> domainService.convertToDto(domain))
                .collect(Collectors.toList());
        return ResponseEntity.ok(domainDtos);
    }
    
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getDomainStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCount", domainService.getDomainPage(1, 1, null, null, null).getTotalElements());
        stats.put("expiredCount", domainService.countExpiredDomains());
        stats.put("expiringCount", domainService.countExpiringInOneMonth());
        stats.put("totalCost", domainService.getTotalDomainCost());
        return ResponseEntity.ok(stats);
    }

    @PostMapping
    public ResponseEntity<Domain> createDomain(@RequestBody Domain domain) {
        Domain savedDomain = domainService.saveDomain(domain);
        return ResponseEntity.ok(savedDomain);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Domain> updateDomain(@PathVariable Long id, @RequestBody Domain domain) {
        Domain existingDomain = domainService.getDomainById(id);
        if (existingDomain == null) {
            return ResponseEntity.notFound().build();
        }

        domain.setId(id);
        Domain updatedDomain = domainService.updateDomain(domain);
        return ResponseEntity.ok(updatedDomain);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDomain(@PathVariable Long id) {
        Domain existingDomain = domainService.getDomainById(id);
        if (existingDomain == null) {
            return ResponseEntity.notFound().build();
        }

        domainService.deleteDomain(id);
        return ResponseEntity.ok().build();
    }
}