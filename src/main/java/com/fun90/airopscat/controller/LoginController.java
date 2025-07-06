package com.fun90.airopscat.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class LoginController {

    @Value("${spring.application.name}")
    private String appName;

    @GetMapping("/login")
    public String loginPage(Model model,
                            @RequestParam(required = false) String error,
                            HttpServletRequest request)  {
        model.addAttribute("appName", appName);

        if (error != null) {
            // 检查request中是否有error属性
            Object errorMsg = request.getAttribute("error");
            if (errorMsg != null) {
                model.addAttribute("error", errorMsg.toString());
            } else {
                // 从session中获取error属性
                HttpSession session = request.getSession(false);
                if (session != null) {
                    errorMsg = session.getAttribute("error");
                    if (errorMsg != null) {
                        model.addAttribute("error", errorMsg.toString());
                        session.removeAttribute("error"); // 使用后清除
                    } else {
                        model.addAttribute("error", "登录失败，请检查您的用户名和密码。");
                    }
                } else {
                    model.addAttribute("error", "登录失败，请检查您的用户名和密码。");
                }
            }
        }
        return "login";
    }
}