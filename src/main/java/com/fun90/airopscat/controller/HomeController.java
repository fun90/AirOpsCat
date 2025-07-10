package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.vo.MenuItem;
import com.fun90.airopscat.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;

@Controller
public class HomeController {

    @Value("${spring.application.name}")
    private String appName;

    private final UserService userService;

    private final Map<String, MenuItem> menu = new HashMap<>();

    @Autowired
    public HomeController(UserService userService) {
        this.userService = userService;
        menu.put("/person/user", new MenuItem("人员", "用户管理", "添加用户，编辑用户，查看用户", "/person/user"));
        menu.put("/person/account", new MenuItem("人员", "账户管理", "添加账户，编辑账户，查看账户详情/列表", "/person/account"));
        menu.put("/person/account-traffic", new MenuItem("人员", "账户流量", "查看流程统计", "/person/account-traffic"));
        menu.put("/person/user-panel", new MenuItem("人员", "用户面板","查看账户信息和客户端配置", "/person/user-panel"));
        menu.put("/device/domain", new MenuItem("设备", "域名", "添加域名，编辑域名，查看域名", "/device/domain"));
        menu.put("/device/server", new MenuItem("设备", "服务器", "添加服务器，编辑服务器，查看服务器", "/device/server"));
        menu.put("/vpn/node", new MenuItem("代理", "节点管理", "添加、编辑、部署节点，查看节点", "/vpn/node"));
        menu.put("/vpn/server-config", new MenuItem("代理", "配置管理", "查看服务器上对应的配置", "/vpn/server-config"));
        menu.put("/money/transactions", new MenuItem("财务", "交易流水", "查看收入、支出等流水", "/money/transactions"));
        menu.put("/system/tag", new MenuItem("系统", "标签管理", "添加标签，编辑标签，查看标签", "/system/tag"));
    }

    @RequestMapping( "/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("appName", appName);
        model.addAttribute("moduleTitle", "人员");
        model.addAttribute("pageTitle", "用户面板");
        model.addAttribute("pageSecondaryTitle", "查看您的账户信息和客户端配置");
        model.addAttribute("uri", "/person/user-panel");
        return "layout";
    }

    @RequestMapping("/console/{module}/{page}")
    public String console(Model model, @PathVariable String module, @PathVariable String page) {
        String uri = "/" + module + "/" + page;
        if (menu.containsKey(uri)) {
            MenuItem breadcrumb = menu.get(uri);
            model.addAttribute("appName", appName);
            model.addAttribute("moduleTitle", breadcrumb.getModuleTitle());
            model.addAttribute("pageTitle", breadcrumb.getTitle());
            model.addAttribute("pageSecondaryTitle", breadcrumb.getSecondaryTitle());
            model.addAttribute("uri", breadcrumb.getUri());
            return "layout";
        }
        return "404";
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