package com.fun90.airopscat.service;

import com.fun90.airopscat.model.convert.NodeConverter;
import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.dto.SubscrptionDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.utils.ConfigFileReader;
import com.fun90.airopscat.utils.ThymeleafUtil;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SubscriptionService {

    private final AccountRepository accountRepository;
    private final TagService tagService;
    private final ThymeleafUtil thymeleafUtil;
    private final String ruleUrl;

    @Autowired
    public SubscriptionService(
            AccountRepository accountRepository,
            TagService tagService,
            ThymeleafUtil thymeleafUtil,
            @Value("${airopscat.rule.url}") String ruleUrl) {
        this.accountRepository = accountRepository;
        this.tagService = tagService;
        this.thymeleafUtil = thymeleafUtil;
        this.ruleUrl = ruleUrl;
    }

    /**
     * 获取规则内容
     */
    public String getRule(String clientName, String ruleName) {
        String templateName = "rules/" + clientName + "/" + ruleName;
        return ConfigFileReader.readFileContent("templates/subscription/" + templateName);
    }

    /**
     * 生成订阅内容
     */
    public SubscrptionDto generateSubscription(String authCode, String osName, String appName) {
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
        List<NodeDto> activeNodes = availableNodes.stream()
                .filter(node -> node.getDeployed() != null && node.getDeployed() == 1)
                .filter(node -> node.getDisabled() == null || node.getDisabled() == 0)
                .map(NodeConverter::toDto)
                .collect(Collectors.toList());

        if (activeNodes.isEmpty()) {
            return null;
        }

        // 根据应用类型生成配置
        String content = generateConfigByApp(account, activeNodes, osName, appName);
        String expireDate = account.getToDate().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return new SubscrptionDto(account.getUser().getNickName(), content, expireDate, 0L, 500L);
    }

    /**
     * 根据应用类型生成配置
     */
    private String generateConfigByApp(Account account, List<NodeDto> nodes, String osName, String appName) {
        Map<String, Object> templateData = new HashMap<>();
        templateData.put("account", account);
        templateData.put("nodes", nodes);
        templateData.put("osName", osName);
        templateData.put("appName", appName);
        templateData.put("ruleUrl", ruleUrl);
        templateData.put("timestamp", System.currentTimeMillis());

        String templateName = getTemplateName(osName, appName);
        String templateContent = getTemplateContent(templateName);

        if (templateContent == null) {
            return null;
        }

        return thymeleafUtil.processStringTemplate(templateContent, templateData);
    }

    /**
     * 获取模板名称
     */
    private String getTemplateName(String osName, String appName) {
        return appName + ".html";
    }

    /**
     * 获取模板内容
     */
    private String getTemplateContent(String templateName) {
        return ConfigFileReader.readFileContent("templates/subscription/" + templateName);
    }
} 