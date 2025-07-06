package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.ClientRequest;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.service.AccountOnlineIpService;
import com.fun90.airopscat.repository.AccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

@RestController
@RequestMapping("/api/open")
public class OpenController {

    private final AccountOnlineIpService accountOnlineIpService;
    private final AccountRepository accountRepository;

    @Value("${airopscat.apple.id}")
    private String appleId;

    @Value("${airopscat.apple.pwd}")
    private String applePwd;

    @Value("${airopscat.subscription.url}")
    private String subscriptionUrl;

    @Value("${airopscat.api.token}")
    private String apiToken;

    @Autowired
    public OpenController(AccountOnlineIpService accountOnlineIpService, AccountRepository accountRepository) {
        this.accountOnlineIpService = accountOnlineIpService;
        this.accountRepository = accountRepository;
    }

    @PostMapping("/account/online/{nodeIp}")
    public ResponseEntity<Void> access(@RequestBody ClientRequest request, @PathVariable String nodeIp, @RequestHeader("Token") String requestToken) {
        // 验证API Token
        if (!this.apiToken.equals(requestToken)) {
            return ResponseEntity.status(401).build();
        }

        accountOnlineIpService.updateOnlineStatus(request, nodeIp);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/docs-info/{authCode}")
    public ResponseEntity<?> getDocsInfo(@PathVariable String authCode) {
        // 查找账户
        Optional<Account> accountOpt = accountRepository.findByAuthCode(authCode);
        if (accountOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的认证码，账户不存在"));
        }
        // 组装subscriptionUrl
        String base = subscriptionUrl + "/config/" + authCode;
        Map<String, String> subscriptionUrls = Map.of(
            "windows", base + "/windows/clash-verge",
            "linux", base + "/linux/clash-verge",
            "ios", base + "/ios/shadowroket",
            "macos", base + "/macos/clash-verge",
            "android", base + "/android/clash-meta"
        );
        Map<String, Object> result = new HashMap<>();
        result.put("subscriptionUrl", subscriptionUrls);
        result.put("appleId", appleId);
        result.put("applePwd", applePwd);
        result.put("nickName", accountOpt.get().getUser().getNickName());
        return ResponseEntity.ok(result);
    }

}
