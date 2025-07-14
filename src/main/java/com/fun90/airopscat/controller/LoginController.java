package com.fun90.airopscat.controller;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Path("/login")
@Produces(MediaType.TEXT_HTML)
public class LoginController {

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "AirOpsCat")
    String appName;

    @Inject
    Template login;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String loginPage(@QueryParam("error") String error) {
        TemplateInstance instance = login.data("appName", appName);

        // 总是传递error属性，避免模板解析错误
        if (error != null) {
            // 根据错误参数提供相应的错误信息
            String errorMessage = getErrorMessage(error);
            instance = instance.data("error", errorMessage);
        } else {
            instance = instance.data("error", "");
        }
        
        return instance.render();
    }
    
    private String getErrorMessage(String error) {
        switch (error) {
            case "true":
            case "1":
                return "登录失败，请检查您的用户名和密码。";
            case "expired":
                return "会话已过期，请重新登录。";
            case "unauthorized":
                return "访问未授权，请先登录。";
            case "locked":
                return "账户已被锁定，请联系管理员。";
            default:
                return "登录失败，请重试。";
        }
    }
}