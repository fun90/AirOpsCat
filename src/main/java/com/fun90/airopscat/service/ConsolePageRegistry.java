package com.fun90.airopscat.service;

import com.fun90.airopscat.model.vo.ConsoleMenuGroup;
import com.fun90.airopscat.model.vo.ConsoleMenuItem;
import com.fun90.airopscat.model.vo.ConsolePage;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class ConsolePageRegistry {

    private static final String DEFAULT_MODAL_PREFIX = "item-";

    private final Map<String, ConsolePage> pages = Map.ofEntries(
            Map.entry("/person/user", page("person", "人员", 10, "person", "用户管理", 10,
                    "添加用户、编辑用户、查看用户", "/person/user", "person/user/page",
                    true, false, false, true, "添加用户", "user-")),
            Map.entry("/person/account", page("person", "人员", 10, "person", "账户管理", 20,
                    "添加账户、编辑账户、查看账户详情列表", "/person/account", "person/account/page",
                    true, true, false, true, "添加账户", "account-")),
            Map.entry("/person/account-traffic", page("person", "人员", 10, "person", "账户流量", 30,
                    "查看流量统计", "/person/account-traffic", "person/account-traffic/page",
                    true, true, false, true, "", DEFAULT_MODAL_PREFIX)),
            Map.entry("/person/user-panel", page("person", "人员", 10, "person", "用户面板", 40,
                    "查看您的账户信息和客户端配置", "/person/user-panel", "person/user-panel/page",
                    true, false, false, true, "", DEFAULT_MODAL_PREFIX)),
            Map.entry("/device/domain", page("device", "设备", 20, "device", "域名", 10,
                    "添加域名、编辑域名、查看域名", "/device/domain", "device/domain/page",
                    true, false, false, true, "添加域名", "domain-")),
            Map.entry("/device/dns-provider", page("device", "设备", 20, "device", "DNS服务商", 15,
                    "管理 DNS 服务商配置和连接测试", "/device/dns-provider", "device/dns-provider/page",
                    true, false, false, true, "添加服务商", "dns-provider-")),
            Map.entry("/device/dns-record", page("device", "设备", 20, "device", "DNS记录", 16,
                    "按域名管理本地 DNS 记录工作区", "/device/dns-record", "device/dns-record/page",
                    false, false, false, true, "添加记录", "dns-record-")),
            Map.entry("/device/server", page("device", "设备", 20, "device", "服务器", 20,
                    "添加服务器、编辑服务器、查看服务器", "/device/server", "device/server/page",
                    true, false, false, true, "添加服务器", "server-")),
            Map.entry("/device/server-monitor", page("device", "设备", 20, "device", "服务器监控", 25,
                    "查看服务器 CPU、内存和网络监控图表", "/device/server-monitor", "device/server-monitor/page",
                    false, false, true, false, "", DEFAULT_MODAL_PREFIX)),
            Map.entry("/device/server-maintenance", page("device", "设备", 20, "device", "服务器运维", 30,
                    "选择运维脚本，按顺序执行并查看每一步的结果", "/device/server-maintenance", "device/server-maintenance/page",
                    true, false, false, false, "", DEFAULT_MODAL_PREFIX)),
            Map.entry("/vpn/node", page("vpn", "代理", 30, "vpn", "节点管理", 10,
                    "添加、编辑、部署节点，查看节点", "/vpn/node", "vpn/node/page",
                    true, true, false, true, "添加节点", "node-")),
            Map.entry("/vpn/node-online-account-stats", page("vpn", "代理", 30, "vpn", "节点在线趋势", 15,
                    "查看节点每日在线账户数量趋势", "/vpn/node-online-account-stats", "vpn/node-online-account-stats/page",
                    false, false, true, false, "", DEFAULT_MODAL_PREFIX)),
            Map.entry("/vpn/node-online-account-overview", page("vpn", "代理", 30, "vpn", "全部节点在线趋势", 16,
                    "查看所有节点每日在线账户数量趋势", "/vpn/node-online-account-overview", "vpn/node-online-account-overview/page",
                    false, false, true, false, "", DEFAULT_MODAL_PREFIX)),
            Map.entry("/vpn/route-rule", page("vpn", "代理", 30, "vpn", "路由规则", 20,
                    "为 sing-box 管理路由规则", "/vpn/route-rule", "vpn/route-rule/page",
                    true, false, false, true, "添加规则", "route-rule-")),
            Map.entry("/vpn/server-config", page("vpn", "代理", 30, "vpn", "配置管理", 30,
                    "查看服务器上对应的配置", "/vpn/server-config", "vpn/server-config/page",
                    true, false, false, true, "添加配置", "serverConfig-")),
            Map.entry("/money/transactions", page("money", "财务", 40, "money", "交易流水", 10,
                    "查看收入、支出等流水", "/money/transactions", "money/transactions/page",
                    true, true, true, true, "", DEFAULT_MODAL_PREFIX)),
            Map.entry("/system/tag", page("system", "系统", 50, "system", "标签管理", 10,
                    "添加标签、编辑标签、查看标签", "/system/tag", "system/tag/page",
                    true, false, false, true, "添加标签", "tag-")),
            Map.entry("/system/config", page("system", "系统", 50, "system", "系统配置", 15,
                    "可视化维护 Bark 和后续系统参数", "/system/config", "system/config/page",
                    true, false, false, false, "", DEFAULT_MODAL_PREFIX)),
            Map.entry("/system/scheduled-task", page("system", "系统", 50, "system", "定时任务", 20,
                    "查看任务调度状态，并支持暂停、恢复和手动执行", "/system/scheduled-task", "system/scheduled-task/page",
                    true, false, false, false, "", DEFAULT_MODAL_PREFIX)),
            Map.entry("/system/backup", page("system", "系统", 50, "system", "数据备份", 30,
                    "查看和管理系统数据备份文件", "/system/backup", "system/backup/page",
                    true, false, false, false, "", "backup-")),
            Map.entry("/system/alert", page("system", "系统", 50, "system", "告警管理", 40,
                    "查看当前活跃告警与历史告警，支持确认和清除操作", "/system/alert", "system/alert/page",
                    true, false, false, false, "", "alert-")),
            Map.entry("/system/request-log", page("system", "系统", 50, "system", "请求日志", 50,
                    "查看公开入口和订阅入口的系统请求日志与统计图表", "/system/request-log", "system/request-log/page",
                    true, false, true, false, "", "request-log-"))
    );
    private final List<ConsoleMenuGroup> menuGroups = buildMenuGroups();

    public ConsolePage getPage(String uri) {
        return pages.get(uri);
    }

    public ConsolePage getDashboardPage() {
        return getPage("/person/user-panel");
    }

    public List<ConsoleMenuGroup> getMenuGroups() {
        return menuGroups;
    }

    private List<ConsoleMenuGroup> buildMenuGroups() {
        Map<String, List<ConsolePage>> groupedPages = pages.values().stream()
                .filter(ConsolePage::showInMenu)
                .sorted(Comparator.comparingInt(ConsolePage::moduleOrder)
                        .thenComparingInt(ConsolePage::menuOrder))
                .collect(Collectors.groupingBy(ConsolePage::moduleKey, LinkedHashMap::new, Collectors.toList()));

        return groupedPages.values().stream()
                .map(modulePages -> {
                    ConsolePage firstPage = modulePages.getFirst();
                    List<ConsoleMenuItem> items = modulePages.stream()
                            .sorted(Comparator.comparingInt(ConsolePage::menuOrder))
                            .map(page -> new ConsoleMenuItem(page.menuTitle(), page.uri(), "/console" + page.uri(), page.menuOrder()))
                            .toList();
                    return new ConsoleMenuGroup(
                            firstPage.moduleKey(),
                            firstPage.moduleTitle(),
                            firstPage.moduleIconKey(),
                            firstPage.moduleOrder(),
                            items
                    );
                })
                .sorted(Comparator.comparingInt(ConsoleMenuGroup::order))
                .toList();
    }

    private static ConsolePage page(String moduleKey, String moduleTitle, int moduleOrder, String moduleIconKey,
                                    String title, int menuOrder, String secondaryTitle, String uri,
                                    String contentTemplate, boolean showInMenu, boolean requiresTomSelect,
                                    boolean requiresCharts, boolean showAddButton,
                                    String buttonText, String modalIdPrefix) {
        return new ConsolePage(
                moduleKey,
                moduleTitle,
                moduleOrder,
                moduleIconKey,
                title,
                title,
                menuOrder,
                secondaryTitle,
                uri,
                contentTemplate,
                showInMenu,
                requiresTomSelect,
                requiresCharts,
                showAddButton,
                buttonText,
                modalIdPrefix
        );
    }
}
