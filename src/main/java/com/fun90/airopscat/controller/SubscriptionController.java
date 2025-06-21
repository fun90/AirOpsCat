package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.service.SubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/subscribe")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @Autowired
    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/{authCode}/{osName}/{appName}")
    public ResponseEntity<String> getSubscription(
            @PathVariable String authCode,
            @PathVariable String osName,
            @PathVariable String appName) {
        
        try {
            String subscriptionContent = subscriptionService.generateSubscription(authCode, osName, appName);
            
            if (subscriptionContent == null) {
                return ResponseEntity.notFound().build();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + appName + ".yaml\"");
            headers.set("charset", StandardCharsets.UTF_8.name());
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(subscriptionContent);
        } catch (Exception e) {
            log.error("Error generating subscription: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }
} 