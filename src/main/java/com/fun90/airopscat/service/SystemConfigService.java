package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.SystemConfigGroupDto;
import com.fun90.airopscat.model.dto.SystemConfigItemDto;
import com.fun90.airopscat.model.dto.SystemConfigUpdateRequest;
import com.fun90.airopscat.model.entity.SystemConfig;
import com.fun90.airopscat.repository.SystemConfigRepository;
import com.fun90.airopscat.util.CryptoUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.Config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@ApplicationScoped
public class SystemConfigService {

    private static final String INPUT_TEXT = "text";
    private static final String INPUT_PASSWORD = "password";
    private static final String INPUT_URL = "url";
    private static final String INPUT_NUMBER = "number";
    private static final String INPUT_CHECKBOX = "checkbox";

    private final SystemConfigRepository systemConfigRepository;
    private final CryptoUtil cryptoUtil;
    private final Config config;

    private final Map<String, ConfigGroupDefinition> groupDefinitions;
    private final Map<String, ConfigItemDefinition> itemDefinitions;
    private final Map<String, Optional<String>> resolvedValueCache;

    @Inject
    public SystemConfigService(SystemConfigRepository systemConfigRepository, CryptoUtil cryptoUtil, Config config) {
        this.systemConfigRepository = systemConfigRepository;
        this.cryptoUtil = cryptoUtil;
        this.config = config;
        this.groupDefinitions = buildGroupDefinitions();
        this.itemDefinitions = indexItemDefinitions(groupDefinitions);
        this.resolvedValueCache = new ConcurrentHashMap<>();
    }

    public List<SystemConfigGroupDto> getConfigGroups() {
        return groupDefinitions.values().stream()
                .sorted((left, right) -> Integer.compare(left.sortOrder(), right.sortOrder()))
                .map(this::toGroupDto)
                .toList();
    }

    public SystemConfigGroupDto getConfigGroup(String groupKey) {
        return toGroupDto(requireGroupDefinition(groupKey));
    }

    @Transactional
    public void initializeDefaultConfigs() {
        for (ConfigGroupDefinition groupDefinition : groupDefinitions.values()) {
            for (ConfigItemDefinition itemDefinition : groupDefinition.items().values()) {
                initializeDefaultConfig(groupDefinition.groupKey(), itemDefinition);
            }
        }
        cleanupObsoleteConfigs();
    }

    public String getResolvedValue(String key) {
        ConfigItemDefinition definition = requireItemDefinition(key);
        return getResolvedValue(definition);
    }

    public int getIntValue(String key, int defaultValue) {
        return parseInteger(getResolvedValue(key), defaultValue);
    }

    public long getLongValue(String key, long defaultValue) {
        return parseLong(getResolvedValue(key), defaultValue);
    }

    public double getDoubleValue(String key, double defaultValue) {
        return parseDouble(getResolvedValue(key), defaultValue);
    }

    public boolean getBooleanValue(String key, boolean defaultValue) {
        String value = getResolvedValue(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    @Transactional
    public SystemConfigGroupDto saveGroup(String groupKey, SystemConfigUpdateRequest request) {
        ConfigGroupDefinition groupDefinition = requireGroupDefinition(groupKey);
        Map<String, String> values = request == null || request.getValues() == null
                ? Map.of()
                : request.getValues();

        for (ConfigItemDefinition itemDefinition : groupDefinition.items().values()) {
            saveSingleValue(groupDefinition.groupKey(), itemDefinition, values.get(itemDefinition.key()));
        }

        return toGroupDto(groupDefinition);
    }

    private void saveSingleValue(String groupKey, ConfigItemDefinition definition, String rawValue) {
        if (!definition.editable()) {
            return;
        }

        String normalizedValue = normalizeValue(rawValue, definition.inputType());
        if (definition.required() && !hasText(normalizedValue) && !isBooleanInput(definition.inputType())) {
            throw new IllegalArgumentException(definition.label() + "不能为空");
        }

        Optional<SystemConfig> optional = systemConfigRepository.findOptionalByConfigKey(definition.key());
        SystemConfig entity = optional.orElseGet(SystemConfig::new);
        entity.setConfigKey(definition.key());
        entity.setGroupKey(groupKey);
        entity.setConfigValue(toStoredValue(normalizedValue, definition.storageEncrypted()));
        if (entity.getId() == null) {
            systemConfigRepository.persist(entity);
        }
        cacheResolvedValue(definition.key(), normalizedValue);
    }

    private void initializeDefaultConfig(String groupKey, ConfigItemDefinition definition) {
        if (systemConfigRepository.findOptionalByConfigKey(definition.key()).isPresent()) {
            return;
        }

        SystemConfig entity = new SystemConfig();
        entity.setConfigKey(definition.key());
        entity.setGroupKey(groupKey);
        String normalizedValue = normalizeValue(definition.defaultValue(), definition.inputType());
        entity.setConfigValue(toStoredValue(normalizedValue, definition.storageEncrypted()));
        systemConfigRepository.persist(entity);
        cacheResolvedValue(definition.key(), normalizedValue);
    }

    private String getResolvedValue(ConfigItemDefinition definition) {
        return resolvedValueCache.computeIfAbsent(
                definition.key(),
                key -> Optional.ofNullable(resolveValue(definition))
        ).orElse(null);
    }

    private String resolveValue(ConfigItemDefinition definition) {
        Optional<SystemConfig> stored = systemConfigRepository.findOptionalByConfigKey(definition.key());
        if (stored.isPresent()) {
            return normalizeValue(fromStoredValue(stored.get().getConfigValue(), definition.storageEncrypted()), definition.inputType());
        }

        return normalizeValue(
                config.getOptionalValue(definition.key(), String.class).orElse(definition.defaultValue()),
                definition.inputType()
        );
    }

    private void cacheResolvedValue(String key, String value) {
        resolvedValueCache.put(key, Optional.ofNullable(value));
    }

    private String toStoredValue(String value, boolean storageEncrypted) {
        if (!storageEncrypted || !hasText(value)) {
            return value;
        }
        return cryptoUtil.encrypt(value);
    }

    private String fromStoredValue(String value, boolean storageEncrypted) {
        if (!storageEncrypted || !hasText(value)) {
            return value;
        }
        return cryptoUtil.decrypt(value);
    }

    private SystemConfigGroupDto toGroupDto(ConfigGroupDefinition definition) {
        List<SystemConfigItemDto> items = definition.items().values().stream()
                .map(itemDefinition -> SystemConfigItemDto.builder()
                        .key(itemDefinition.key())
                        .label(itemDefinition.label())
                        .description(itemDefinition.description())
                        .inputType(itemDefinition.inputType())
                        .required(itemDefinition.required())
                        .sensitive(itemDefinition.sensitive())
                        .restartRequired(itemDefinition.restartRequired())
                        .editable(itemDefinition.editable())
                        .storageEncrypted(itemDefinition.storageEncrypted())
                        .placeholder(itemDefinition.placeholder())
                        .value(getResolvedValue(itemDefinition))
                        .build())
                .toList();

        return SystemConfigGroupDto.builder()
                .groupKey(definition.groupKey())
                .title(definition.title())
                .description(definition.description())
                .testSupported(definition.testSupported())
                .sortOrder(definition.sortOrder())
                .items(items)
                .build();
    }

    private ConfigGroupDefinition requireGroupDefinition(String groupKey) {
        ConfigGroupDefinition definition = groupDefinitions.get(groupKey);
        if (definition == null) {
            throw new IllegalArgumentException("不支持的配置分组: " + groupKey);
        }
        return definition;
    }

    private ConfigItemDefinition requireItemDefinition(String key) {
        ConfigItemDefinition definition = itemDefinitions.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("不支持的配置项: " + key);
        }
        return definition;
    }

    private Map<String, ConfigGroupDefinition> buildGroupDefinitions() {
        Map<String, ConfigGroupDefinition> groups = new LinkedHashMap<>();

        groups.put("bark", group(
                "bark",
                "Bark 通知",
                "维护 Bark 地址、加密参数和默认通知选项。",
                10,
                true,
                item("airopscat.bark.url", "Bark 地址", "Bark 服务地址。", INPUT_URL, true, false, false, true, "https://push.example.com", ""),
                item("airopscat.bark.device-key", "设备 Key", "推送目标设备的 Bark Key。", INPUT_PASSWORD, true, true, false, true, "请输入 Bark 设备 Key", "", true),
                item("airopscat.bark.encrypt-enabled", "启用加密", "按 Bark 加密协议发送 ciphertext 和 iv。", INPUT_CHECKBOX, false, false, false, true, "", "false"),
                item("airopscat.bark.encrypt-key", "AES Key", "启用加密时必须为 32 字节。", INPUT_PASSWORD, false, true, false, true, "32字节 AES Key", "", true),
                item("airopscat.bark.encrypt-iv", "AES IV", "启用加密时必须为 16 字节。", INPUT_PASSWORD, false, true, false, true, "16字节 AES IV", "", true),
                item("airopscat.bark.default-group", "默认分组", "未指定 group 时使用。", INPUT_TEXT, false, false, false, true, "AirOpsCat", "AirOpsCat"),
                item("airopscat.bark.default-sound", "默认铃声", "未指定 sound 时使用。", INPUT_TEXT, false, false, false, true, "healthnotification", "healthnotification"),
                item("airopscat.bark.default-icon", "默认图标", "未指定 icon 时使用。", INPUT_URL, false, false, false, true, "https://static.example.com/icon.png", "")
        ));

        groups.put("open", group(
                "open",
                "开放接口",
                "订阅地址、对外文档和开放接口相关配置。",
                40,
                false,
                item("airopscat.subscription.url", "订阅地址", "生成订阅和客户端配置时使用。", INPUT_URL, true, false, false, true, "http://localhost:8080/subscribe", "http://localhost:8080/subscribe"),
                item("airopscat.docs.url", "文档地址", "控制台展示的文档入口地址。", INPUT_URL, false, false, false, true, "https://docs.xxx.com", "https://docs.xxx.com"),
                item("airopscat.request-log.enabled", "请求日志开关", "是否记录开放接口和订阅入口的系统请求日志。", INPUT_CHECKBOX, false, false, false, true, "", "true"),
                item("airopscat.request-log.paths", "请求日志路径", "逗号分隔的请求日志记录路径，支持 /path/* 前缀匹配。", INPUT_TEXT, true, false, false, true, "/api/open/docs-info/*,/subscribe/*", "/api/open/docs-info/*,/subscribe/*"),
                item("airopscat.domain", "系统域名", "运维脚本和对外地址使用的域名。", INPUT_TEXT, false, false, false, true, "yourdomain.com", "yourdomain.com"),
                item("airopscat.api.token", "接口 Token", "开放接口和运维脚本使用的 Token。", INPUT_PASSWORD, false, true, false, true, "your_api_token_here", "your_api_token_here", true),
                item("airopscat.apple.id", "Apple ID", "开放接口返回的 Apple ID。", INPUT_TEXT, false, true, false, true, "your_apple_id_here", "your_apple_id_here", true),
                item("airopscat.apple.pwd", "Apple 密码", "开放接口返回的 Apple 密码。", INPUT_PASSWORD, false, true, false, true, "your_apple_pwd_here", "your_apple_pwd_here", true)
        ));

        groups.put("account", group(
                "account",
                "账号设置",
                "账号统计展示相关参数。",
                45,
                false,
                item("airopscat.account.multiplier", "账号倍数", "账号总数和在线数等统计数值的展示倍率，设为 1 时不放大。", INPUT_NUMBER, true, false, false, true, "1", "1"),
                item("airopscat.account.traffic-over-quota.download-mbps", "超配下行降速（Mbps）", "账户流量超额后的下行限速值，单位 Mbps，最小 1。", INPUT_NUMBER, true, false, false, true, "1", "1"),
                item("airopscat.account.traffic-over-quota.upload-mbps", "超配上行降速（Mbps）", "账户流量超额后的上行限速值，单位 Mbps，最小 1。", INPUT_NUMBER, true, false, false, true, "1", "1"),
                item("airopscat.ratelimit.enabled", "启用限速", "全局 sing-box 原生限速开关，关闭后部署和同步均不注入限速配置。", INPUT_CHECKBOX, false, false, false, true, "", "false")
        ));

        groups.put("template", group(
                "template",
                "模板与运维",
                "模板目录和服务器运维脚本运行参数。",
                50,
                false,
                item("airopscat.config.templates.dir", "模板目录", "订阅和核心配置模板目录。", INPUT_TEXT, true, false, false, true, "./config", "./config"),
                item("airopscat.install.scripts.dir", "运维脚本目录", "服务器运维脚本的本地存放目录，兼容原一键安装脚本目录配置。", INPUT_TEXT, true, false, false, true, "./config/shell", "./config/shell"),
                item("airopscat.install.remote-work-dir", "远端工作目录", "服务器运维脚本在服务器上的工作目录。", INPUT_TEXT, true, false, false, true, "/tmp/airopscat-maintenance", "/tmp/airopscat-installer"),
                item("airopscat.deployment.max-parallel-servers", "部署并发服务器数", "单轮节点部署最多并发处理的服务器数。", INPUT_NUMBER, true, false, false, true, "4", "4")
        ));

        groups.put("monitor", group(
                "monitor",
                "监控与在线状态",
                "在线检测、监控采集和告警相关参数。",
                60,
                false,
                item("airopscat.online.check-minutes", "在线检测分钟数", "在线账号检测时间窗口。", INPUT_NUMBER, true, false, false, true, "10", "10"),
                item("airopscat.account.online.refresh-minutes", "在线刷新间隔分钟数", "通过 Clash API 主动采集在线状态的间隔。", INPUT_NUMBER, true, false, false, true, "5", "5"),
                item("airopscat.account.online.cleanup.batch-size", "在线清理批大小", "在线记录清理单批删除数量。", INPUT_NUMBER, true, false, false, true, "1000", "1000"),
                item("airopscat.account.online.history.retention-hours", "在线记录保留小时数", "定期清理超过此小时数的历史在线连接记录，任务执行间隔与此值相同。", INPUT_NUMBER, true, false, false, true, "8", "8"),
                item("airopscat.node.online-account.stats.sample-minutes", "节点在线趋势采样间隔分钟数", "采样当前在线账户数并沉淀为节点每日在线账户趋势的间隔。", INPUT_NUMBER, true, false, false, true, "5", "5"),
                item("airopscat.account.connection-limit.alert.enabled", "连接数超限告警", "账户当前在线连接数超过最大连接数时发送告警。", INPUT_CHECKBOX, false, false, false, true, "", "true"),
                item("airopscat.account.connection-limit.alert.min-interval-minutes", "连接数告警间隔分钟数", "同一账户连接数持续超限时，两次通知之间的最小间隔。", INPUT_NUMBER, true, false, false, true, "60", "60"),
                item("airopscat.account.connection-limit.alert.recovery-notify-enabled", "连接数恢复通知", "账户连接数恢复到限制以内时是否发送恢复通知。", INPUT_CHECKBOX, false, false, false, true, "", "false"),
                item("airopscat.sing-box.clash-api.host", "Clash API 远端地址", "sing-box Clash API 监听地址。", INPUT_TEXT, true, false, false, true, "127.0.0.1", "127.0.0.1"),
                item("airopscat.sing-box.clash-api.port", "Clash API 远端端口", "sing-box Clash API 监听端口。", INPUT_NUMBER, true, false, false, true, "19191", "19191"),
                item("airopscat.sing-box.clash-api.secret", "Clash API Secret", "Clash API Bearer Token，留空时不验证。", INPUT_PASSWORD, false, true, false, true, "", "", true),
                item("airopscat.sing-box.clash-api.timeout-seconds", "Clash API 超时秒数", "HTTP 请求超时时间。", INPUT_NUMBER, true, false, false, true, "5", "5"),
                item("airopscat.sing-box.clash-api.max-retries", "Clash API 重试次数", "Clash API 查询失败后的重试次数。", INPUT_NUMBER, true, false, false, true, "1", "1"),
                item("airopscat.connection-snapshot.online.enabled", "在线快照优先", "在线账号刷新优先读取节点侧在线连接快照。", INPUT_CHECKBOX, false, false, false, true, "", "true"),
                item("airopscat.connection-snapshot.online.path", "在线快照路径", "节点侧在线连接快照文件路径。", INPUT_TEXT, true, false, false, true, "/run/airopscat/online-connections.json", "/run/airopscat/online-connections.json"),
                item("airopscat.connection-snapshot.online.fallback-clash-api-enabled", "快照失败降级", "在线连接快照不可用时是否降级为 Clash API 直连采集。", INPUT_CHECKBOX, false, false, false, true, "", "true"),
                item("airopscat.server.monitor.enabled", "启用监控", "是否启用服务器监控和负载告警。", INPUT_CHECKBOX, false, false, false, true, "", "true"),
                item("airopscat.server.monitor.refresh-minutes", "采集间隔分钟数", "服务器监控采集间隔。", INPUT_NUMBER, true, false, false, true, "2", "2"),
                item("airopscat.server.monitor.max-parallel-servers", "监控并发服务器数", "单轮监控采集和提醒最多并发处理的服务器数。", INPUT_NUMBER, true, false, false, true, "10", "10"),
                item("airopscat.server.monitor.retention-days", "监控保留天数", "服务器监控数据保留天数。", INPUT_NUMBER, true, false, false, true, "30", "30"),
                item("airopscat.server.monitor.cleanup.batch-size", "监控清理批大小", "监控历史清理单批删除数量。", INPUT_NUMBER, true, false, false, true, "1000", "1000"),
                item("airopscat.server.monitor.cleanup.cron", "监控清理 Cron", "监控历史清理调度表达式。", INPUT_TEXT, true, false, false, true, "0 0 3 * * ?", "0 0 3 * * ?"),
                item("airopscat.server.monitor.alert.cron", "监控告警 Cron", "监控告警调度表达式。", INPUT_TEXT, true, false, false, true, "0 */5 * * * ?", "0 */5 * * * ?"),
                item("airopscat.server.traffic.notify.cron", "流量阈值提醒 Cron", "服务器流量阈值提醒调度表达式。", INPUT_TEXT, true, false, false, true, "0 10 10 * * ?", "0 10 10 * * ?"),
                item("airopscat.server.monitor.alert.cpu-threshold", "CPU 告警阈值", "支持 0-1 或 0-100 写法。", INPUT_NUMBER, true, false, false, true, "0.9", "0.9"),
                item("airopscat.server.monitor.alert.memory-threshold", "内存告警阈值", "支持 0-1 或 0-100 写法。", INPUT_NUMBER, true, false, false, true, "0.95", "0.95"),
                item("airopscat.server.monitor.alert.traffic-threshold", "流量告警阈值", "支持 0-1 或 0-100 写法。", INPUT_NUMBER, true, false, false, true, "0.85", "0.85"),
                item("airopscat.server.monitor.alert.continuous-minutes", "阈值持续分钟数", "达到阈值后持续多久才触发告警。", INPUT_NUMBER, true, false, false, true, "30", "30"),
                item("airopscat.server.monitor.alert.min-interval-minutes", "监控告警间隔分钟数", "同一服务器同一负载指标持续超限时，两次通知之间的最小间隔。", INPUT_NUMBER, true, false, false, true, "60", "60"),
                item("airopscat.server.vnstat.collect-minutes", "vnstat 采集间隔分钟数", "通过 vnstat 采集服务器网卡月度流量的间隔分钟数。", INPUT_NUMBER, true, false, false, true, "5", "5"),
                item("airopscat.server.expiring.alert.min-interval-hours", "服务器到期提醒间隔小时数", "同一服务器到期提醒两次通知之间的最小间隔。", INPUT_NUMBER, true, false, false, true, "23", "23"),
                item("airopscat.domain.expiring.alert.min-interval-hours", "域名到期提醒间隔小时数", "同一域名到期提醒两次通知之间的最小间隔。", INPUT_NUMBER, true, false, false, true, "23", "23"),
                item("airopscat.account.expiring.alert.min-interval-hours", "账号到期提醒间隔小时数", "同一账号到期提醒两次通知之间的最小间隔。", INPUT_NUMBER, true, false, false, true, "23", "23")
        ));

        groups.put("history", group(
                "history",
                "历史清理",
                "历史明细表的保留策略与批处理参数。",
                65,
                false,
                item("airopscat.node.deployment.history.retention-days", "部署历史保留天数", "节点部署历史明细保留天数。", INPUT_NUMBER, true, false, false, true, "90", "90"),
                item("airopscat.node.deployment.history.keep-latest-per-node", "部署历史保底版本数", "每个节点至少保留的最近历史版本数。", INPUT_NUMBER, true, false, false, true, "20", "20"),
                item("airopscat.node.deployment.history.cleanup.batch-size", "部署历史清理批大小", "节点部署历史单批删除数量。", INPUT_NUMBER, true, false, false, true, "500", "500"),
                item("airopscat.account.traffic.retention-days", "账户流量保留天数", "账户流量明细保留天数。", INPUT_NUMBER, true, false, false, true, "180", "180"),
                item("airopscat.account.traffic.cleanup.batch-size", "账户流量清理批大小", "账户流量明细单批删除数量。", INPUT_NUMBER, true, false, false, true, "1000", "1000"),
                item("airopscat.server.traffic.retention-days", "服务器流量保留天数", "服务器流量明细保留天数。", INPUT_NUMBER, true, false, false, true, "180", "180"),
                item("airopscat.server.traffic.cleanup.batch-size", "服务器流量清理批大小", "服务器流量明细单批删除数量。", INPUT_NUMBER, true, false, false, true, "1000", "1000"),
                item("airopscat.request-log.retention-days", "请求日志保留天数", "系统请求日志自动清理时保留的天数。", INPUT_NUMBER, true, false, false, true, "30", "30")
        ));

        groups.put("backup", group(
                "backup",
                "数据库备份",
                "备份路径、调度和保留策略配置。",
                70,
                false,
                item("airopscat.backup.dir", "备份目录", "数据库备份文件存储目录。", INPUT_TEXT, true, false, false, true, "./backup", "./backup"),
                item("airopscat.backup.cron", "备份 Cron", "自动备份调度表达式。", INPUT_TEXT, true, false, false, true, "0 0 6 * * ?", "0 0 6 * * ?"),
                item("airopscat.backup.cleanup.cron", "备份清理 Cron", "备份清理调度表达式。", INPUT_TEXT, true, false, false, true, "0 30 6 * * ?", "0 30 6 * * ?"),
                item("airopscat.backup.retention-days", "备份保留天数", "自动清理时保留的备份天数。", INPUT_NUMBER, true, false, false, true, "30", "30"),
                item("airopscat.backup.mysqldump-path", "mysqldump 路径", "数据库备份工具路径。", INPUT_TEXT, true, false, false, true, "mysqldump", "mysqldump")
        ));

        groups.put("scheduled", group(
                "scheduled",
                "定时任务",
                "统一管理通用定时任务的调度表达式。",
                80,
                false,
                item("airopscat.account.expiration.cron", "过期账号处理 Cron", "检查过期账号并重新部署关联节点。", INPUT_TEXT, true, false, false, true, "0 0 5 * * ?", "0 0 5 * * ?"),
                item("airopscat.expiration.notify.cron", "资源到期提醒 Cron", "账号、服务器、域名到期提醒调度表达式。", INPUT_TEXT, true, false, false, true, "0 0 10 * * ?", "0 0 10 * * ?"),
                item("airopscat.traffic.stats.cron", "流量统计采集 Cron", "采集账号和服务器流量统计的调度表达式。", INPUT_TEXT, true, false, false, true, "0 */15 * * * ?", "0 */15 * * * ?"),
                item("airopscat.node.deployment.history.cleanup.cron", "部署历史清理 Cron", "清理过期节点部署历史的调度表达式。", INPUT_TEXT, true, false, false, true, "0 20 3 * * ?", "0 20 3 * * ?"),
                item("airopscat.account.traffic.cleanup.cron", "账户流量清理 Cron", "清理过期账户流量明细的调度表达式。", INPUT_TEXT, true, false, false, true, "0 40 3 * * ?", "0 40 3 * * ?"),
                item("airopscat.server.traffic.cleanup.cron", "服务器流量清理 Cron", "清理过期服务器流量明细的调度表达式。", INPUT_TEXT, true, false, false, true, "0 0 4 * * ?", "0 0 4 * * ?"),
                item("airopscat.request-log.cleanup.cron", "请求日志清理 Cron", "清理过期系统请求日志的调度表达式。", INPUT_TEXT, true, false, false, true, "0 30 3 * * ?", "0 30 3 * * ?"),
                item("airopscat.core.config.cleanup.cron", "内核配置清理 Cron", "清理内核配置旧备份文件的调度表达式。", INPUT_TEXT, true, false, false, true, "0 0 8 * * ?", "0 0 8 * * ?")
        ));

        return groups;
    }

    private ConfigGroupDefinition group(String groupKey,
                                        String title,
                                        String description,
                                        int sortOrder,
                                        boolean testSupported,
                                        ConfigItemDefinition... items) {
        Map<String, ConfigItemDefinition> itemMap = new LinkedHashMap<>();
        for (ConfigItemDefinition item : items) {
            itemMap.put(item.key(), item);
        }
        return new ConfigGroupDefinition(groupKey, title, description, sortOrder, testSupported, itemMap);
    }

    private ConfigItemDefinition item(String key,
                                      String label,
                                      String description,
                                      String inputType,
                                      boolean required,
                                      boolean sensitive,
                                      boolean restartRequired,
                                      boolean editable,
                                      String placeholder,
                                      String defaultValue) {
        return item(key, label, description, inputType, required, sensitive, restartRequired, editable, placeholder, defaultValue, false);
    }

    private ConfigItemDefinition item(String key,
                                      String label,
                                      String description,
                                      String inputType,
                                      boolean required,
                                      boolean sensitive,
                                      boolean restartRequired,
                                      boolean editable,
                                      String placeholder,
                                      String defaultValue,
                                      boolean storageEncrypted) {
        return new ConfigItemDefinition(
                key,
                label,
                description,
                inputType,
                required,
                sensitive,
                restartRequired,
                editable,
                placeholder,
                defaultValue,
                storageEncrypted
        );
    }

    private Map<String, ConfigItemDefinition> indexItemDefinitions(Map<String, ConfigGroupDefinition> groups) {
        Map<String, ConfigItemDefinition> result = new LinkedHashMap<>();
        groups.values().forEach(group -> group.items().forEach(result::put));
        return result;
    }

    private void cleanupObsoleteConfigs() {
        LinkedHashSet<String> definedKeys = new LinkedHashSet<>(itemDefinitions.keySet());
        for (SystemConfig systemConfig : systemConfigRepository.listAll()) {
            if (!definedKeys.contains(systemConfig.getConfigKey())) {
                log.info("删除过时系统配置项: {}", systemConfig.getConfigKey());
                systemConfigRepository.delete("configKey", systemConfig.getConfigKey());
                resolvedValueCache.remove(systemConfig.getConfigKey());
            }
        }
    }

    private String normalizeValue(String value, String inputType) {
        if (value == null) {
            return isBooleanInput(inputType) ? "false" : null;
        }
        if (isBooleanInput(inputType)) {
            return String.valueOf(Boolean.parseBoolean(value));
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBooleanInput(String inputType) {
        return Objects.equals(INPUT_CHECKBOX, inputType);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private int parseInteger(String value, int defaultValue) {
        if (!hasText(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long parseLong(String value, long defaultValue) {
        if (!hasText(value)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private double parseDouble(String value, double defaultValue) {
        if (!hasText(value)) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private record ConfigGroupDefinition(
            String groupKey,
            String title,
            String description,
            int sortOrder,
            boolean testSupported,
            Map<String, ConfigItemDefinition> items
    ) {
    }

    private record ConfigItemDefinition(
            String key,
            String label,
            String description,
            String inputType,
            boolean required,
            boolean sensitive,
            boolean restartRequired,
            boolean editable,
            String placeholder,
            String defaultValue,
            boolean storageEncrypted
    ) {
    }
}
