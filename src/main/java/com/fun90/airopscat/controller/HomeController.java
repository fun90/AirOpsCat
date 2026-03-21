package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.vo.MenuItem;
import io.quarkus.qute.Template;
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
    Template layout;

    @Inject
    Template notFound;

    private final Map<String, MenuItem> menu = new HashMap<>();

    @Inject
    public HomeController() {
        menu.put("/person/user", new MenuItem("人员", "用户管理", "添加用户、编辑用户、查看用户", "/person/user"));
        menu.put("/person/account", new MenuItem("人员", "账户管理", "添加账户、编辑账户、查看账户详情列表", "/person/account"));
        menu.put("/person/account-traffic", new MenuItem("人员", "账户流量", "查看流量统计", "/person/account-traffic"));
        menu.put("/person/user-panel", new MenuItem("人员", "用户面板", "查看您的账户信息和客户端配置", "/person/user-panel"));
        menu.put("/device/domain", new MenuItem("设备", "域名", "添加域名、编辑域名、查看域名", "/device/domain"));
        menu.put("/device/server", new MenuItem("设备", "服务器", "添加服务器、编辑服务器、查看服务器", "/device/server"));
        menu.put("/device/server-install", new MenuItem("设备", "一键装机", "选择装机脚本，按顺序执行并查看每一步的结果", "/device/server-install"));
        menu.put("/vpn/node", new MenuItem("代理", "节点管理", "添加、编辑、部署节点，查看节点", "/vpn/node"));
        menu.put("/vpn/route-rule", new MenuItem("代理", "路由规则", "为 xray、sing-box 管理路由规则", "/vpn/route-rule"));
        menu.put("/vpn/server-config", new MenuItem("代理", "配置管理", "查看服务器上对应的配置", "/vpn/server-config"));
        menu.put("/money/transactions", new MenuItem("财务", "交易流水", "查看收入、支出等流水", "/money/transactions"));
        menu.put("/system/tag", new MenuItem("系统", "标签管理", "添加标签、编辑标签、查看标签", "/system/tag"));
        menu.put("/system/backup", new MenuItem("系统", "数据备份", "查看和管理系统数据备份文件", "/system/backup"));
    }

    @GET
    @Path("/dashboard")
    @Produces(MediaType.TEXT_HTML)
    @Authenticated
    public String dashboard() {
        return renderPage("人员", "用户面板", "查看您的账户信息和客户端配置", "/person/user-panel", false);
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
        MenuItem breadcrumb = menu.get(uri);
        if (breadcrumb == null) {
            return notFound.instance().render();
        }

        return renderPage(
                breadcrumb.getModuleTitle(),
                breadcrumb.getTitle(),
                breadcrumb.getSecondaryTitle(),
                breadcrumb.getUri(),
                shouldShowAddButton(uri)
        );
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

    private String renderPage(String moduleTitle, String pageTitle, String secondaryTitle, String uri, boolean showAddButton) {
        return layout.data("appName", appName)
                .data("moduleTitle", moduleTitle)
                .data("pageTitle", pageTitle)
                .data("pageSecondaryTitle", secondaryTitle)
                .data("uri", uri)
                .data("showAddButton", showAddButton)
                .data("buttonText", getButtonTextForPage(uri))
                .data("modalIdPrefix", getModalPrefixForPage(uri))
                .render();
    }

    private boolean shouldShowAddButton(String uri) {
        return !"/system/backup".equals(uri) && !"/device/server-install".equals(uri);
    }

    private String getButtonTextForPage(String uri) {
        return switch (uri) {
            case "/person/user" -> "添加用户";
            case "/person/account" -> "添加账户";
            case "/device/domain" -> "添加域名";
            case "/device/server" -> "添加服务器";
            case "/vpn/node" -> "添加节点";
            case "/vpn/route-rule" -> "添加规则";
            case "/vpn/server-config" -> "添加配置";
            case "/system/tag" -> "添加标签";
            default -> "";
        };
    }

    private String getModalPrefixForPage(String uri) {
        return switch (uri) {
            case "/person/user" -> "user-";
            case "/person/account" -> "account-";
            case "/device/domain" -> "domain-";
            case "/device/server" -> "server-";
            case "/vpn/node" -> "node-";
            case "/vpn/route-rule" -> "route-rule-";
            case "/vpn/server-config" -> "serverConfig-";
            case "/system/tag" -> "tag-";
            case "/system/backup" -> "backup-";
            default -> "item-";
        };
    }
}
