package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.vo.MenuItem;
import com.fun90.airopscat.service.UserService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
@Path("/")
public class HomeController {

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "AirOpsCat")
    String appName;

    @Inject
    UserService userService;

    @Inject
    Template layout;

    @Inject
    Template notFound;

    private final Map<String, MenuItem> menu = new HashMap<>();

    @Inject
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

    @GET
    @Path("/dashboard")
    @Produces(MediaType.TEXT_HTML)
    @Authenticated
    public String dashboard() {
        return layout.data("appName", appName)
                .data("moduleTitle", "人员")
                .data("pageTitle", "用户面板")
                .data("pageSecondaryTitle", "查看您的账户信息和客户端配置")
                .data("uri", "/person/user-panel")
                .data("showAddButton", false)
                .data("buttonText", "")
                .data("modalIdPrefix", "")
                .render();
    }

    @GET
    @Path("/")
    public Response rootRedirect() {
        return Response.status(302).location(URI.create("/dashboard")).build();
    }

    @GET
    @Path("/console/{module}/{page}")
    @Produces(MediaType.TEXT_HTML)
    @Authenticated
    public String console(@PathParam("module") String module, @PathParam("page") String page) {
        String uri = "/" + module + "/" + page;
        if (menu.containsKey(uri)) {
            MenuItem breadcrumb = menu.get(uri);
            return layout.data("appName", appName)
                    .data("moduleTitle", breadcrumb.getModuleTitle())
                    .data("pageTitle", breadcrumb.getTitle())
                    .data("pageSecondaryTitle", breadcrumb.getSecondaryTitle())
                    .data("uri", breadcrumb.getUri())
                    .data("showAddButton", true)
                    .data("buttonText", getButtonTextForPage(uri))
                    .data("modalIdPrefix", getModalPrefixForPage(uri))
                    .render();
        }
        return notFound.instance().render();
    }

    @GET
    @Path("/api/admin/data")
    @RolesAllowed("ADMIN")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getAdminData() {
        return Response.ok("This is admin data, only accessible to admins").build();
    }
    
    @GET
    @Path("/api/partner/data")
    @RolesAllowed({"ADMIN", "PARTNER"})
    @Produces(MediaType.TEXT_PLAIN)
    public Response getPartnerData() {
        return Response.ok("This is partner data, accessible to partners and admins").build();
    }
    
    @GET
    @Path("/api/vip/data")
    @RolesAllowed({"ADMIN", "PARTNER", "VIP"})
    @Produces(MediaType.TEXT_PLAIN)
    public Response getVipData() {
        return Response.ok("This is VIP data, accessible to VIPs, partners, and admins").build();
    }
    
    private String getButtonTextForPage(String uri) {
        switch (uri) {
            case "/person/user":
                return "添加用户";
            case "/person/account":
                return "添加账户";
            case "/device/domain":
                return "添加域名";
            case "/device/server":
                return "添加服务器";
            case "/vpn/node":
                return "添加节点";
            case "/vpn/server-config":
                return "添加配置";
            case "/system/tag":
                return "添加标签";
            default:
                return "添加";
        }
    }
    
    private String getModalPrefixForPage(String uri) {
        switch (uri) {
            case "/person/user":
                return "user-";
            case "/person/account":
                return "account-";
            case "/device/domain":
                return "domain-";
            case "/device/server":
                return "server-";
            case "/vpn/node":
                return "node-";
            case "/vpn/server-config":
                return "serverConfig-";
            case "/system/tag":
                return "tag-";
            default:
                return "item-";
        }
    }
}