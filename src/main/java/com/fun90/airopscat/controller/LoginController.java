package com.fun90.airopscat.controller;

import com.fun90.airopscat.util.ErrorMessageEncoder;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Slf4j
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
    public String loginPage(@QueryParam("error") String error, 
                           @QueryParam("msg") String encodedMsg,
                           @Context RoutingContext context) {
        TemplateInstance instance = login.data("appName", appName);

        // 总是传递error属性，避免模板解析错误
        if (error != null) {
            // 根据错误参数提供相应的错误信息
            String errorMessage = getErrorMessage(context, encodedMsg);
            instance = instance.data("error", errorMessage);
        } else {
            instance = instance.data("error", "");
        }

        return instance.render();
    }

    private String getErrorMessage(RoutingContext context, String encodedMsg) {
        String errorMessage = "";

        // 优先使用session中的错误信息
        try {
            // 尝试获取或创建session
            if (context.session() != null) {
                String sessionError = context.session().get("loginError");
                if (sessionError != null) {
                    errorMessage = sessionError;
                    // 清除session中的错误信息
                    context.session().remove("loginError");
                }
            }
        } catch (Exception e) {
            // Session获取失败时，忽略错误，使用默认处理
            log.error("Failed to get session: {}", e.getMessage(), e);
        }

        // 如果session中没有错误信息，使用查询参数
        if (errorMessage.isEmpty()) {
            if (encodedMsg != null && !encodedMsg.isEmpty()) {
                // 解码编码的错误消息
                try {
                    errorMessage = ErrorMessageEncoder.decode(encodedMsg);
                } catch (Exception e) {
                    log.warn("Failed to decode error message: {}", encodedMsg, e);
                    errorMessage = "登录失败，请重试。";
                }
            }
        }

        return errorMessage;
    }
}