package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.ClientRequest;
import com.fun90.airopscat.service.AccountOnlineIpService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/open")
public class OpenController {

    private final AccountOnlineIpService accountOnlineIpService;

    @Autowired
    public OpenController(AccountOnlineIpService accountOnlineIpService) {
        this.accountOnlineIpService = accountOnlineIpService;
    }

    @PostMapping("/account/online/{nodeIp}")
    public ResponseEntity<Void> access(@RequestBody ClientRequest request, @PathVariable String nodeIp, @RequestHeader("Token") String apiToken) {
        // TODO: 验证apiToken的合法性
        // 这里可以添加token验证逻辑

        accountOnlineIpService.updateOnlineStatus(request, nodeIp);
        return ResponseEntity.ok().build();
    }
}
