package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.service.UserService;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthController {
    
    @Inject
    UserService userService;
    
    @Inject
    SecurityIdentity securityIdentity;

    // 删除自定义的authenticate方法，使用Quarkus内置的JPA Security认证

    @GET
    @Path("/user")
    @Blocking
    public Response getCurrentUser() {
        if (!securityIdentity.isAnonymous()) {
            String email = securityIdentity.getPrincipal().getName();
            User user = userService.getByEmail(email);
            if (user != null) {
                return Response.ok(userService.convertToDto(user)).build();
            }
        }
        return Response.status(Response.Status.BAD_REQUEST).entity("User not authenticated").build();
    }

    @GET
    @Path("/logout")
    @Blocking
    public Response logout(@Context RoutingContext context) {
        try {
            // 在 Quarkus 中，使用 RoutingContext 来清除会话
            if (context != null && context.session() != null) {
                // 清除会话
                context.session().destroy();
            }
            
            // 返回成功响应，并清除认证cookie
            return Response.ok()
                    .entity("{\"message\": \"Logged out successfully\"}")
                    .header("Set-Cookie", "quarkus-credential=; Path=/; Max-Age=0; SameSite=Strict")
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Logout failed\"}")
                    .build();
        }
    }
}