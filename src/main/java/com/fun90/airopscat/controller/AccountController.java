package com.fun90.airopscat.controller;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fun90.airopscat.model.dto.AccountDto;
import com.fun90.airopscat.model.dto.AccountOnlineIpDto;
import com.fun90.airopscat.model.dto.AccountRequest;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.model.enums.PeriodType;
import com.fun90.airopscat.service.AccountOnlineIpService;
import com.fun90.airopscat.service.AccountService;
import com.fun90.airopscat.service.TagService;
import com.fun90.airopscat.service.UserService;

@RestController
@RequestMapping("/api/admin/accounts")
public class AccountController {
    
    private final AccountService accountService;
    private final UserService userService;
    private final TagService tagService;
    private final AccountOnlineIpService accountOnlineIpService;
    
    @Autowired
    public AccountController(AccountService accountService, UserService userService, TagService tagService, AccountOnlineIpService accountOnlineIpService) {
        this.accountService = accountService;
        this.userService = userService;
        this.tagService = tagService;
        this.accountOnlineIpService = accountOnlineIpService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAccountPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status
    ) {
        Page<Account> accountPage = accountService.getAccountPage(page, size, search, userId, status);
        
        // Convert to DTOs
        List<AccountDto> accountDtos = accountPage.getContent().stream()
                .map(account -> accountService.convertToDto(account))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", accountDtos);
        response.put("total", accountPage.getTotalElements());
        response.put("pages", accountPage.getTotalPages());
        response.put("current", page);
        response.put("size", size);
        
        // Add statistics
        response.put("stats", accountService.getAccountsStats());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountDto> getAccountById(@PathVariable Long id) {
        Account account = accountService.getAccountById(id);
        if (account != null) {
            AccountDto dto = accountService.convertToDto(account);
            return ResponseEntity.ok(dto);
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> getAccountsByUser(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search
    ) {
        Page<Account> accountPage = accountService.getAccountPage(page, size, search, userId, null);
        
        // Convert to DTOs
        List<AccountDto> accountDtos = accountPage.getContent().stream()
                .map(account -> accountService.convertToDto(account))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", accountDtos);
        response.put("total", accountPage.getTotalElements());
        response.put("pages", accountPage.getTotalPages());
        response.put("current", page);
        response.put("size", size);
        response.put("user", userService.getUserById(userId));

        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/period-types")
    public ResponseEntity<List<Map<String, String>>> getPeriodTypes() {
        List<Map<String, String>> periodTypes = Stream.of(PeriodType.values())
                .map(type -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("value", type.name());
                    map.put("label", type.getDescription());
                    return map;
                })
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(periodTypes);
    }
    
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> getAccountsStats() {
        return ResponseEntity.ok(accountService.getAccountsStats());
    }
    
    @GetMapping("/my-accounts")
    public ResponseEntity<Map<String, Object>> getMyAccounts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search
    ) {
        // 获取当前登录用户
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }
        
        String email = authentication.getName();
        User currentUser = userService.getByEmail(email).orElse(null);
        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }
        
        // 获取当前用户的账户
        Page<Account> accountPage = accountService.getAccountPage(page, size, search, currentUser.getId(), null);
        
        // Convert to DTOs
        List<AccountDto> accountDtos = accountPage.getContent().stream()
                .map(account -> accountService.convertToDto(account))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", accountDtos);
        response.put("total", accountPage.getTotalElements());
        response.put("pages", accountPage.getTotalPages());
        response.put("current", page);
        response.put("size", size);
        response.put("user", currentUser);

        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<Account> createAccount(@RequestBody AccountRequest request) {
        // 创建Account实体
        Account account = new Account();
        account.setUserId(request.getUserId());
        account.setAccountNo(request.getAccountNo());
        account.setLevel(request.getLevel());
        account.setFromDate(request.getFromDate());
        account.setToDate(request.getToDate());
        account.setPeriodType(request.getPeriodType());
        account.setUuid(request.getUuid());
        account.setAuthCode(request.getAuthCode());
        account.setMaxOnlineIps(request.getMaxOnlineIps());
        account.setSpeed(request.getSpeed());
        account.setBandwidth(request.getBandwidth());
        account.setDisabled(request.getDisabled());
        
        // 保存账户
        Account savedAccount = accountService.saveAccount(account);
        
        // 处理标签关联
        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            tagService.updateAccountTags(savedAccount.getId(), request.getTagIds());
        }
        
        return ResponseEntity.ok(savedAccount);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AccountDto> updateAccount(@PathVariable Long id, @RequestBody AccountRequest request) {
        Account existingAccount = accountService.getAccountById(id);
        if (existingAccount == null) {
            return ResponseEntity.notFound().build();
        }

        // 更新Account实体
        Account account = new Account();
        account.setId(id);
        account.setUserId(request.getUserId());
        account.setAccountNo(request.getAccountNo());
        account.setLevel(request.getLevel());
        account.setFromDate(request.getFromDate());
        account.setToDate(request.getToDate());
        account.setPeriodType(request.getPeriodType());
        account.setUuid(request.getUuid());
        account.setAuthCode(request.getAuthCode());
        account.setMaxOnlineIps(request.getMaxOnlineIps());
        account.setSpeed(request.getSpeed());
        account.setBandwidth(request.getBandwidth());
        account.setDisabled(request.getDisabled());
        
        Account updatedAccount = accountService.updateAccount(account);
        
        // 处理标签关联
        if (request.getTagIds() != null) {
            tagService.updateAccountTags(updatedAccount.getId(), request.getTagIds());
        }
        
        return ResponseEntity.ok(accountService.convertToDto(updatedAccount));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAccount(@PathVariable Long id) {
        Account existingAccount = accountService.getAccountById(id);
        if (existingAccount == null) {
            return ResponseEntity.notFound().build();
        }

        accountService.deleteAccount(id);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/enable")
    public ResponseEntity<Map<String, Object>> enableAccount(@PathVariable Long id) {
        Account account = accountService.toggleAccountStatus(id, false);
        if (account != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 0);
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }

    @PatchMapping("/{id}/disable")
    public ResponseEntity<Map<String, Object>> disableAccount(@PathVariable Long id) {
        Account account = accountService.toggleAccountStatus(id, true);
        if (account != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 1);
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }
    
    @PatchMapping("/{id}/renew")
    public ResponseEntity<AccountDto> renewAccount(
            @PathVariable Long id, 
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime expiryDate
    ) {
        Account account = accountService.renewAccount(id, expiryDate);
        AccountDto dto = accountService.convertToDto(account);
        return ResponseEntity.ok(dto);
    }
    
    @PatchMapping("/{id}/reset-auth")
    public ResponseEntity<AccountDto> resetAuthCode(@PathVariable Long id) {
        Account account = accountService.resetAuthCode(id);
        AccountDto dto = accountService.convertToDto(account);
        return ResponseEntity.ok(dto);
    }
    
    @GetMapping("/{id}/config-url")
    public ResponseEntity<Map<String, String>> getConfigUrl(@PathVariable Long id, @RequestParam String osName, @RequestParam String appName) {
        Account account = accountService.getAccountById(id);
        if (account != null) {
            Map<String, String> response = new HashMap<>();
            response.put("configUrl", accountService.getConfigUrl(account, osName, appName));
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/online/accountNo/{accountNo}")
    public ResponseEntity<List<AccountOnlineIpDto>> getOnlineRecordsByAccountNo(@PathVariable String accountNo) {
        List<AccountOnlineIpDto> records = accountOnlineIpService.getOnlineRecordsByAccountNo(accountNo);
        return ResponseEntity.ok(records);
    }
    
    @GetMapping("/online/node/{nodeIp}")
    public ResponseEntity<List<AccountOnlineIpDto>> getOnlineRecordsByNodeIp(@PathVariable String nodeIp) {
        List<AccountOnlineIpDto> records = accountOnlineIpService.getOnlineRecordsByNodeIp(nodeIp);
        return ResponseEntity.ok(records);
    }
    
    @GetMapping("/online/all")
    public ResponseEntity<List<AccountOnlineIpDto>> getAllOnlineRecords() {
        List<AccountOnlineIpDto> records = accountOnlineIpService.getAllOnlineRecords();
        return ResponseEntity.ok(records);
    }
    
    @DeleteMapping("/online/cleanup")
    public ResponseEntity<Void> cleanupExpiredRecords() {
        accountOnlineIpService.cleanupExpiredRecords();
        return ResponseEntity.ok().build();
    }
}