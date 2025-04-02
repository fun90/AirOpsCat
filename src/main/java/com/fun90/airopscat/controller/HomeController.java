package com.fun90.airopscat.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class HomeController {

    @Value("${spring.application.name}")
    private String appName;

    @GetMapping("/login")
    public String loginPage(Model model) {
        model.addAttribute("appName", appName);
        return "login";
    }

    @RequestMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("appName", appName);
        return "dashboard";
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