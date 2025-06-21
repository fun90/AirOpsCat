package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.utils.ConfigFileReader;
import com.fun90.airopscat.utils.MustacheUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SubscriptionService {

    private final AccountRepository accountRepository;
    private final TagService tagService;

    @Autowired
    public SubscriptionService(
            AccountRepository accountRepository,
            TagService tagService) {
        this.accountRepository = accountRepository;
        this.tagService = tagService;
    }

    /**
     * 生成订阅内容
     */
    public String generateSubscription(String authCode, String osName, String appName) {
        // 验证参数
        if (!StringUtils.hasText(authCode) || !StringUtils.hasText(osName) || !StringUtils.hasText(appName)) {
            return null;
        }

        // 根据authCode查找账户
        Optional<Account> optionalAccount = accountRepository.findByAuthCode(authCode);
        if (optionalAccount.isEmpty()) {
            return null;
        }

        Account account = optionalAccount.get();
        
        // 检查账户状态
        if (!account.isActive()) {
            return null;
        }

        // 获取账户可用的节点
        List<Node> availableNodes = tagService.getAvailableNodesByAccount(account.getId());
        if (availableNodes.isEmpty()) {
            return null;
        }

        // 过滤已部署且启用的节点
        List<Node> activeNodes = availableNodes.stream()
                .filter(node -> node.getDeployed() != null && node.getDeployed() == 1)
                .filter(node -> node.getDisabled() == null || node.getDisabled() == 0)
                .collect(Collectors.toList());

        if (activeNodes.isEmpty()) {
            return null;
        }

        // 根据应用类型生成配置
        return generateConfigByApp(account, activeNodes, osName, appName);
    }

    /**
     * 根据应用类型生成配置
     */
    private String generateConfigByApp(Account account, List<Node> nodes, String osName, String appName) {
        Map<String, Object> templateData = new HashMap<>();
        templateData.put("account", account);
        templateData.put("nodes", nodes);
        templateData.put("osName", osName);
        templateData.put("appName", appName);
        templateData.put("timestamp", System.currentTimeMillis());

        // 构建节点代理信息
        List<Map<String, Object>> proxies = buildProxies(nodes, account);
        templateData.put("proxies", proxies);

        // 构建代理组信息
        List<String> proxyNames = proxies.stream()
                .map(proxy -> (String) proxy.get("name"))
                .collect(Collectors.toList());
        templateData.put("proxyNames", proxyNames);

        String templateName = getTemplateName(osName, appName);
        String templateContent = getTemplateContent(templateName);

        if (templateContent == null) {
            return null;
        }

        return MustacheUtil.processTemplate(templateContent, templateData);
    }

    /**
     * 构建代理配置信息
     */
    private List<Map<String, Object>> buildProxies(List<Node> nodes, Account account) {
        List<Map<String, Object>> proxies = new ArrayList<>();

        for (Node node : nodes) {
            if (node.getServer() == null) {
                continue;
            }

            Map<String, Object> proxy = new HashMap<>();
            proxy.put("name", buildProxyName(node));
            proxy.put("type", node.getProtocol().toLowerCase());
            proxy.put("server", node.getServer().getHost());
            proxy.put("port", node.getPort());

            // 根据协议类型添加特定配置
            addProtocolSpecificConfig(proxy, node, account);

            proxies.add(proxy);
        }

        return proxies;
    }

    /**
     * 添加协议特定配置
     */
    private void addProtocolSpecificConfig(Map<String, Object> proxy, Node node, Account account) {
        String protocol = node.getProtocol().toLowerCase();

        switch (protocol) {
            case "vless":
                proxy.put("uuid", account.getUuid());
                proxy.put("alterId", 0);
                // 可以根据inbound配置添加更多参数
                break;
            case "shadowsocks":
                proxy.put("password", account.getAuthCode());
                proxy.put("cipher", "chacha20-ietf-poly1305");
                break;
            case "socks":
                proxy.put("username", account.getUuid());
                proxy.put("password", account.getAuthCode());
                break;
            case "hysteria2":
                proxy.put("password", account.getAuthCode());
                break;
        }
    }

    /**
     * 构建代理名称
     */
    private String buildProxyName(Node node) {
        if (StringUtils.hasText(node.getName())) {
            return node.getName();
        }
        return node.getServer().getName() + "-" + node.getProtocol() + "-" + node.getPort();
    }

    /**
     * 获取模板名称
     */
    private String getTemplateName(String osName, String appName) {
        return "subscription/" + osName + "/" + appName + ".mustache";
    }

    /**
     * 获取模板内容
     */
    private String getTemplateContent(String templateName) {
        try {
            return ConfigFileReader.readFileContent("templates/" + templateName);
        } catch (Exception e) {
            // 如果找不到特定模板，尝试使用通用模板
            try {
                return getDefaultTemplate(templateName);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    /**
     * 获取默认模板
     */
    private String getDefaultTemplate(String templateName) {
        if (templateName.contains("clash")) {
            return getClashTemplate();
        } else if (templateName.contains("shadowrocket") || templateName.contains("loon") || templateName.contains("stash")) {
            return getIosTemplate();
        }
        return getClashTemplate(); // 默认使用Clash模板
    }

    /**
     * Clash通用模板
     */
    private String getClashTemplate() {
        return """
                # AirOpsCat Subscription Config
                # Generated at: {{timestamp}}
                # Account: {{account.uuid}}
                
                port: 7890
                socks-port: 7891
                allow-lan: true
                mode: rule
                log-level: info
                external-controller: 127.0.0.1:9090
                
                dns:
                  enable: true
                  ipv6: false
                  listen: 0.0.0.0:53
                  enhanced-mode: fake-ip
                  fake-ip-range: 198.18.0.1/16
                  nameserver:
                    - 114.114.114.114
                    - 8.8.8.8
                
                proxies:
                {{#proxies}}
                  - name: "{{name}}"
                    type: {{type}}
                    server: {{server}}
                    port: {{port}}
                    {{#uuid}}uuid: {{uuid}}{{/uuid}}
                    {{#password}}password: {{password}}{{/password}}
                    {{#cipher}}cipher: {{cipher}}{{/cipher}}
                    {{#alterId}}alterId: {{alterId}}{{/alterId}}
                    {{#username}}username: {{username}}{{/username}}
                {{/proxies}}
                
                proxy-groups:
                  - name: "♻️ 自动选择"
                    type: url-test
                    proxies:
                {{#proxyNames}}
                      - "{{.}}"
                {{/proxyNames}}
                    url: 'http://www.gstatic.com/generate_204'
                    interval: 300
                
                  - name: "🔰 节点选择"
                    type: select
                    proxies:
                      - "♻️ 自动选择"
                {{#proxyNames}}
                      - "{{.}}"
                {{/proxyNames}}
                
                  - name: "🐟 漏网之鱼"
                    type: select
                    proxies:
                      - "🔰 节点选择"
                      - "DIRECT"
                
                rules:
                  - DOMAIN-SUFFIX,local,DIRECT
                  - IP-CIDR,127.0.0.0/8,DIRECT
                  - IP-CIDR,172.16.0.0/12,DIRECT
                  - IP-CIDR,192.168.0.0/16,DIRECT
                  - IP-CIDR,10.0.0.0/8,DIRECT
                  - IP-CIDR,17.0.0.0/8,DIRECT
                  - IP-CIDR,100.64.0.0/10,DIRECT
                  - GEOIP,CN,DIRECT
                  - MATCH,🐟 漏网之鱼
                """;
    }

    /**
     * iOS应用通用模板
     */
    private String getIosTemplate() {
        return """
                # AirOpsCat Subscription Config
                # Generated at: {{timestamp}}
                # Account: {{account.uuid}}
                
                {{#proxies}}
                {{name}} = {{type}}, {{server}}, {{port}}{{#uuid}}, {{uuid}}{{/uuid}}{{#password}}, {{password}}{{/password}}{{#cipher}}, {{cipher}}{{/cipher}}
                {{/proxies}}
                
                [Proxy Group]
                🔰 节点选择 = select{{#proxyNames}}, {{.}}{{/proxyNames}}
                ♻️ 自动选择 = url-test{{#proxyNames}}, {{.}}{{/proxyNames}}, url = http://www.gstatic.com/generate_204, interval = 600, tolerance = 100
                🐟 漏网之鱼 = select, 🔰 节点选择, DIRECT
                
                [Rule]
                DOMAIN-SUFFIX,local,DIRECT
                IP-CIDR,127.0.0.0/8,DIRECT
                IP-CIDR,172.16.0.0/12,DIRECT
                IP-CIDR,192.168.0.0/16,DIRECT
                IP-CIDR,10.0.0.0/8,DIRECT
                GEOIP,CN,DIRECT
                FINAL,🐟 漏网之鱼
                """;
    }
} 