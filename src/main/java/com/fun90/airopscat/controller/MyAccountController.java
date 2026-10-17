package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.AccountDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.service.AccountService;
import com.fun90.airopscat.service.UserService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 用户面板自助接口：任意已登录用户只能访问自己名下的账户。
 * 不放在 /api/admin 下，否则非管理员角色会被 admin-policy 拒绝。
 */
@Slf4j
@ApplicationScoped
@Path("/api/me/accounts")
@Produces(MediaType.APPLICATION_JSON)
public class MyAccountController {

    @Inject
    SecurityIdentity securityIdentity;
    @Inject
    UserService userService;
    @Inject
    AccountService accountService;

    @GET
    public Response list() {
        User user = currentUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        List<Account> accounts = accountService.getAccountPage(null, user.getId(), null, null).list();
        return Response.ok(accountService.convertToDtoList(accounts).stream().map(this::forUser).toList()).build();
    }

    @POST
    @Path("/{id}/reset-auth")
    public Response resetAuthCode(@PathParam("id") Long id) {
        User user = currentUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        Account account = accountService.getAccountById(id);
        // 非本人账户与不存在一律返回 404，避免探测他人账户 ID
        if (account == null || !user.getId().equals(account.getUserId())) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", "账户不存在")).build();
        }
        Account updated = accountService.resetAuthCode(id);
        log.info("用户 {} 重置了账户 {} 的订阅链接", user.getEmail(), updated.getAccountNo());
        return Response.ok(forUser(accountService.convertToDto(updated))).build();
    }

    private User currentUser() {
        return securityIdentity.isAnonymous() ? null : userService.getByEmail(securityIdentity.getPrincipal().getName());
    }

    /** 管理员备注不对用户展示 */
    private AccountDto forUser(AccountDto dto) {
        dto.setRemark(null);
        return dto;
    }
}
