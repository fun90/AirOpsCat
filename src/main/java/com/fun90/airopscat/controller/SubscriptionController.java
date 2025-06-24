package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.SubscrptionDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.service.SubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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
            @PathVariable String appName,
            @RequestParam(required = false) String view,
            @RequestParam(required = false) String dns) {
        
        try {
            SubscrptionDto subscriptionDto = subscriptionService.generateSubscription(authCode, osName, appName);
            String subscriptionContent = subscriptionDto.getContent();
            
            if (subscriptionContent == null) {
                return ResponseEntity.notFound().build();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            headers.set("charset", StandardCharsets.UTF_8.name());
            // 配置文件的 更新间隔 将被设置为对应的值（单位: 小时）
            headers.set("profile-update-interval", "72");
            if (!"1".equals(view)) {
                headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + subscriptionDto.getName() + ".yaml\"");
            }
            if ("shadowrocket".equals(appName)) {
                String usedFlow = new BigDecimal(subscriptionDto.getUsedFlow()).divide(new BigDecimal(1024 * 1024 * 1024), 2, RoundingMode.HALF_UP).toPlainString();
                headers.set("subscription-userinfo", "tfc: " + usedFlow + "G; exp: " + subscriptionDto.getExpireDate());
            } else {
                headers.set("subscription-userinfo", "download=" + subscriptionDto.getUsedFlow() 
                + "; total=" + subscriptionDto.getTotalFlow() + "; expire=" + subscriptionDto.getExpireDate());
            }
            // String docsIndex ="";
            // headers.set("profile-web-page-url", docsIndex + "?code=" +  subscription.getCode());

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(subscriptionContent);
        } catch (Exception e) {
            log.error("Error generating subscription: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }
} 