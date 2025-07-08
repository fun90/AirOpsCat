package com.fun90.airopscat.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.time.DateFormatUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fun90.airopscat.model.dto.SubscrptionDto;
import com.fun90.airopscat.model.dto.ApiResponseDto;
import com.fun90.airopscat.service.SubscriptionService;

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

    @GetMapping("/config/{authCode}/{osName}/{appName}")
    public ResponseEntity<String> getSubscription(
            @PathVariable String authCode,
            @PathVariable String osName,
            @PathVariable String appName,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String view,
            @RequestParam(required = false) String dns,
            @RequestParam(required = false) String dns2) {
        
        try {
            Map<String, String> params = new HashMap<>();
            if ("1".equals(version)) {
                if (Objects.nonNull(dns2)) {
                    params.put("dns", dns2);
                }
            } else {
                if (Objects.nonNull(dns)) {
                    params.put("dns", dns);
                }
            }
            ApiResponseDto<SubscrptionDto> response = subscriptionService.generateSubscription(authCode, osName, appName, params);
            if (!response.isSuccess()) {
                // 返回错误信息
                return ResponseEntity.badRequest()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("错误: " + response.getMessage());
            }
            
            SubscrptionDto subscriptionDto = response.getData();
            String subscriptionContent = subscriptionDto.getContent();
            
            if (subscriptionContent == null) {
                return ResponseEntity.badRequest()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("错误: 生成配置文件失败");
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            headers.set("charset", StandardCharsets.UTF_8.name());
            // 配置文件的 更新间隔 将被设置为对应的值（单位: 小时）
            headers.set("profile-update-interval", "72");
            if (!"1".equals(view)) {
                String fileName = URLEncoder.encode(subscriptionDto.getFileName(), StandardCharsets.UTF_8);
                headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename*=UTF-8''" + fileName);
            }
            if ("shadowrocket".equalsIgnoreCase(appName)) {
                String usedFlow = new BigDecimal(subscriptionDto.getUsedFlow()).divide(new BigDecimal(1024 * 1024 * 1024), 2, RoundingMode.HALF_UP).toPlainString();
                String expireDate = subscriptionDto.getExpireDate().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
                headers.set("subscription-userinfo", "tfc: " + usedFlow + "G; exp: " + expireDate);
            } else {
                long expire = subscriptionDto.getExpireDate().toEpochSecond(ZoneOffset.of("+8"));
                headers.set("subscription-userinfo", "download=" + subscriptionDto.getUsedFlow()
                + "; total=" + subscriptionDto.getTotalFlow() + "; expire=" + expire);
            }
            // String docsIndex ="";
            // headers.set("profile-web-page-url", docsIndex + "?code=" +  subscription.getCode());

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(subscriptionContent);
        } catch (Exception e) {
            log.error("Error generating subscription: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("错误: 系统内部错误，请联系管理员");
        }
    }

    @GetMapping("/rules/{appType}/{ruleName}")
    public ResponseEntity<String> rule(
        @PathVariable String appType,
        @PathVariable String ruleName) {
            String ruleContent = subscriptionService.getRule(appType, ruleName);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            headers.set("charset", StandardCharsets.UTF_8.name());
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(ruleContent);
    }

    @GetMapping("/nodes/{authCode}/{appType}")
    public ResponseEntity<String> nodes(
        @PathVariable String authCode,
        @PathVariable String appType) {
            String nodesContent = subscriptionService.getNodes(authCode, appType);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            headers.set("charset", StandardCharsets.UTF_8.name());
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(nodesContent);
    }
} 