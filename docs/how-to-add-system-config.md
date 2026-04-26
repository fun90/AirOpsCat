# 新增系统配置项流程

系统配置通过 `SystemConfigService` 统一管理，配置项定义在代码中，值存储在数据库 `system_config` 表。用户可在控制台「系统设置」页面查看和修改。

---

## 步骤一：在 SystemConfigService 注册配置项

文件：`src/main/java/com/fun90/airopscat/service/SystemConfigService.java`

在 `buildGroupDefinitions()` 方法中操作。

### 1a. 加入已有分组

找到合适的分组，追加一个 `item(...)` 调用：

```java
groups.put("monitor", group(
    "monitor",
    "监控与在线状态",
    "...",
    60,
    false,
    // ... 已有 item
    item("airopscat.server.monitor.alert.traffic-threshold", "流量告警阈值", "支持 0-1 或 0-100 写法。",
         INPUT_NUMBER, true, false, false, true, "0.85", "0.85")
));
```

### 1b. 新建分组

在 `buildGroupDefinitions()` 中插入新分组（注意 `sortOrder` 不要与已有分组重复）：

```java
groups.put("account", group(
    "account",         // groupKey，全局唯一
    "账号设置",         // 控制台显示标题
    "账号统计相关参数。", // 描述
    45,                // sortOrder，决定显示顺序
    false,             // testSupported（是否支持连通性测试）
    item("airopscat.account.multiplier", "账号倍数", "统计数值展示倍率。",
         INPUT_NUMBER, true, false, false, true, "1", "1")
));
```

### item() 参数说明

| 参数 | 类型 | 说明 |
|------|------|------|
| key | String | 配置 key，与 `@ConfigProperty(name=...)` 保持一致 |
| label | String | 控制台显示名称 |
| description | String | 说明文字 |
| inputType | String | 输入类型：`INPUT_TEXT` / `INPUT_NUMBER` / `INPUT_PASSWORD` / `INPUT_URL` / `INPUT_CHECKBOX` |
| required | boolean | 是否必填 |
| sensitive | boolean | 是否敏感（控制台脱敏展示） |
| restartRequired | boolean | 修改后是否需要重启生效 |
| editable | boolean | 是否允许在控制台编辑（填 `true`） |
| placeholder | String | 输入框占位文字 |
| defaultValue | String | 默认值（数据库无记录时使用） |
| storageEncrypted | boolean | 是否加密存储（可选，默认 false；仅对密码类字段设为 true） |

---

## 步骤二：在业务代码中读取

注入 `SystemConfigService`，按值类型选择对应方法：

```java
@Inject
SystemConfigService systemConfigService;

// 整数
int value = systemConfigService.getIntValue("airopscat.account.multiplier", 1);

// 浮点数
double threshold = systemConfigService.getDoubleValue("airopscat.server.monitor.alert.traffic-threshold", 0.9);

// 布尔
boolean enabled = systemConfigService.getBooleanValue("airopscat.server.monitor.enabled", true);

// 字符串
String dir = systemConfigService.getResolvedValue("airopscat.install.scripts.dir");

// 长整数
long val = systemConfigService.getLongValue("some.key", 0L);
```

> **注意**：`getResolvedValue` / `getIntValue` 等方法在 key 未注册时会抛出 `IllegalArgumentException`，所以必须先完成步骤一。

### 删除原有 @ConfigProperty

完成上述注入后，同时删除原来的字段及 import：

```java
// 删除这两行
import org.eclipse.microprofile.config.inject.ConfigProperty;
@ConfigProperty(name = "airopscat.xxx", defaultValue = "xxx")
SomeType field;
```

---

## 步骤三：确认默认值初始化

应用启动时 `DataInitializationConfig` 会调用 `systemConfigService.initializeDefaultConfigs()`，将所有已注册但数据库中尚无记录的配置项写入默认值，无需手动执行 SQL。

---

## 已有分组速查

| groupKey | 标题 | sortOrder | 适合放什么 |
|----------|------|-----------|-----------|
| bark | Bark 通知 | 10 | Bark 推送地址、密钥、加密参数 |
| open | 开放接口 | 40 | 订阅地址、文档地址、域名、API Token |
| account | 账号设置 | 45 | 账号展示、统计相关参数 |
| template | 模板与运维 | 50 | 模板目录、运维脚本目录、并发数 |
| monitor | 监控与在线状态 | 60 | 采集间隔、告警阈值、在线检测 |
| history | 历史清理 | 65 | 各类数据保留天数、批大小 |
| backup | 数据库备份 | 70 | 备份目录、Cron、保留天数 |
| scheduled | 定时任务 | 80 | 通用定时任务 Cron 表达式 |
