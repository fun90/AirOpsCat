package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.service.UserService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
public class HomeController {

    @Value("${spring.application.name}")
    private String appName;

    private final UserService userService;

    @Autowired
    public HomeController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/login")
    public String loginPage(Model model) {
        model.addAttribute("appName", appName);
        return "login";
    }

    @RequestMapping("/dashboard")
    public String index(Model model) {
        model.addAttribute("appName", appName);
        List<User> users = userService.findAllUsers();
        model.addAttribute("users", users);
        return "dashboard";
    }

    @RequestMapping("/index2")
    public String index2(Model model) {
        model.addAttribute("appName", appName);
        return "index2";
    }
    
    @GetMapping("/api/admin/data")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseBody
    public String getAdminData() {
        return "This is admin data, only accessible to admins";
    }
    
    @GetMapping("/api/partner/data")
    @PreAuthorize("hasAnyRole('ADMIN', 'PARTNER')")
    @ResponseBody
    public String getPartnerData() {
        return "This is partner data, accessible to partners and admins";
    }
    
    @GetMapping("/api/vip/data")
    @PreAuthorize("hasAnyRole('ADMIN', 'PARTNER', 'VIP')")
    @ResponseBody
    public String getVipData() {
        return "This is VIP data, accessible to VIPs, partners, and admins";
    }
}