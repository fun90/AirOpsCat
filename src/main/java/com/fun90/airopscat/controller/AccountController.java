package com.fun90.airopscat.controller;

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
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
@Path("/api/admin/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccountController {
    
    private final AccountService accountService;
    private final UserService userService;
    private final TagService tagService;
    private final AccountOnlineIpService accountOnlineIpService;
    
    @Inject
    SecurityIdentity securityIdentity;
    
    @Inject
    public AccountController(AccountService accountService, UserService userService, TagService tagService, AccountOnlineIpService accountOnlineIpService) {
        this.accountService = accountService;
        this.userService = userService;
        this.tagService = tagService;
        this.accountOnlineIpService = accountOnlineIpService;
    }

    @GET
    public Response getAccountPage(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("search") String search,
            @QueryParam("userId") Long userId,
            @QueryParam("status") String status
    ) {
        PanacheQuery<Account> accountQuery = accountService.getAccountPage(search, userId, status);
        accountQuery.page(Page.of(page - 1, size));
        
        // Convert to DTOs
        List<Account> accounts = accountQuery.list();
        List<AccountDto> accountDtos = accounts.stream()
                .map(account -> accountService.convertToDto(account))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", accountDtos);
        response.put("total", accountQuery.count());
        response.put("pages", accountQuery.pageCount());
        response.put("current", page);
        response.put("size", size);
        
        // Add statistics
        response.put("stats", accountService.getAccountsStats());

        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}")
    public Response getAccountById(@PathParam("id") Long id) {
        Account account = accountService.getAccountById(id);
        if (account != null) {
            AccountDto dto = accountService.convertToDto(account);
            return Response.ok(dto).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    @GET
    @Path("/user/{userId}")
    public Response getAccountsByUser(
            @PathParam("userId") Long userId,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("search") String search
    ) {
        PanacheQuery<Account> accountQuery = accountService.getAccountPage(search, userId, null);
        accountQuery.page(Page.of(page - 1, size));
        
        // Convert to DTOs
        List<Account> accounts = accountQuery.list();
        List<AccountDto> accountDtos = accounts.stream()
                .map(account -> accountService.convertToDto(account))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", accountDtos);
        response.put("total", accountQuery.count());
        response.put("pages", accountQuery.pageCount());
        response.put("current", page);
        response.put("size", size);
        response.put("user", userService.getUserById(userId));

        return Response.ok(response).build();
    }
    
    @GET
    @Path("/period-types")
    public Response getPeriodTypes() {
        List<Map<String, String>> periodTypes = Stream.of(PeriodType.values())
                .map(type -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("value", type.name());
                    map.put("label", type.getDescription());
                    return map;
                })
                .collect(Collectors.toList());
        
        return Response.ok(periodTypes).build();
    }
    
    @GET
    @Path("/stats")
    public Response getAccountsStats() {
        return Response.ok(accountService.getAccountsStats()).build();
    }
    
    @GET
    @Path("/my-accounts")
    public Response getMyAccounts(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("search") String search
    ) {
        // 获取当前登录用户
        if (securityIdentity.isAnonymous()) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        
        String email = securityIdentity.getPrincipal().getName();
        User currentUser = userService.getByEmail(email);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        
        // 获取当前用户的账户
        PanacheQuery<Account> accountQuery = accountService.getAccountPage(search, currentUser.getId(), null);
        accountQuery.page(Page.of(page - 1, size));
        
        // Convert to DTOs
        List<Account> accounts = accountQuery.list();
        List<AccountDto> accountDtos = accounts.stream()
                .map(account -> accountService.convertToDto(account))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", accountDtos);
        response.put("total", accountQuery.count());
        response.put("pages", accountQuery.pageCount());
        response.put("current", page);
        response.put("size", size);
        response.put("user", currentUser);

        return Response.ok(response).build();
    }

    @POST
    public Response createAccount(AccountRequest request) {
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
        account.setRemark(request.getRemark());
        
        // 保存账户
        Account savedAccount = accountService.saveAccount(account);
        
        // 处理标签关联
        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            tagService.updateAccountTags(savedAccount.getId(), request.getTagIds());
        }
        
        return Response.ok(savedAccount).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateAccount(@PathParam("id") Long id, AccountRequest request) {
        Account existingAccount = accountService.getAccountById(id);
        if (existingAccount == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
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
        account.setRemark(request.getRemark());
        
        Account updatedAccount = accountService.updateAccount(account);
        
        // 处理标签关联
        if (request.getTagIds() != null) {
            tagService.updateAccountTags(updatedAccount.getId(), request.getTagIds());
        }
        
        return Response.ok(accountService.convertToDto(updatedAccount)).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteAccount(@PathParam("id") Long id) {
        Account existingAccount = accountService.getAccountById(id);
        if (existingAccount == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        accountService.deleteAccount(id);
        return Response.ok().build();
    }

    @PATCH
    @Path("/{id}/enable")
    public Response enableAccount(@PathParam("id") Long id) {
        Account account = accountService.toggleAccountStatus(id, false);
        if (account != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 0);
            return Response.ok(response).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @PATCH
    @Path("/{id}/disable")
    public Response disableAccount(@PathParam("id") Long id) {
        Account account = accountService.toggleAccountStatus(id, true);
        if (account != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 1);
            return Response.ok(response).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    @PATCH
    @Path("/{id}/renew")
    public Response renewAccount(
            @PathParam("id") Long id, 
            @QueryParam("expiryDate") String expiryDate
    ) {
        LocalDateTime parsedDate = LocalDateTime.parse(expiryDate);
        Account account = accountService.renewAccount(id, parsedDate);
        AccountDto dto = accountService.convertToDto(account);
        return Response.ok(dto).build();
    }
    
    @PATCH
    @Path("/{id}/reset-auth")
    public Response resetAuthCode(@PathParam("id") Long id) {
        Account account = accountService.resetAuthCode(id);
        AccountDto dto = accountService.convertToDto(account);
        return Response.ok(dto).build();
    }
    
    @GET
    @Path("/{id}/config-url")
    public Response getConfigUrl(@PathParam("id") Long id, @QueryParam("osName") String osName, @QueryParam("appName") String appName) {
        Account account = accountService.getAccountById(id);
        if (account != null) {
            Map<String, String> response = new HashMap<>();
            response.put("configUrl", accountService.getConfigUrl(account, osName, appName));
            return Response.ok(response).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    @GET
    @Path("/online/accountNo/{accountNo}")
    public Response getOnlineRecordsByAccountNo(@PathParam("accountNo") String accountNo) {
        List<AccountOnlineIpDto> records = accountOnlineIpService.getOnlineRecordsByAccountNo(accountNo);
        return Response.ok(records).build();
    }
    
    @GET
    @Path("/online/node/{nodeIp}")
    public Response getOnlineRecordsByNodeIp(@PathParam("nodeIp") String nodeIp) {
        List<AccountOnlineIpDto> records = accountOnlineIpService.getOnlineRecordsByNodeIp(nodeIp);
        return Response.ok(records).build();
    }
    
    @GET
    @Path("/online/all")
    public Response getAllOnlineRecords() {
        List<AccountOnlineIpDto> records = accountOnlineIpService.getAllOnlineRecords();
        return Response.ok(records).build();
    }
    
    @DELETE
    @Path("/online/cleanup")
    public Response cleanupExpiredRecords() {
        accountOnlineIpService.cleanupExpiredRecords();
        return Response.ok().build();
    }
}

@ApplicationScoped
@Path("/api/admin/config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ConfigController {
    
    @Inject
    @ConfigProperty(name = "airopscat.docs.url", defaultValue = "https://docs.xxx.com")
    String docsUrl;
    
    @GET
    @Path("/docs")
    public Response getDocsConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("url", docsUrl);
        return Response.ok(config).build();
    }
}