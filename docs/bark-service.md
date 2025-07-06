# BarkService 使用文档

## 概述

BarkService 是 AirOpsCat 系统中的一个通知服务，用于通过 HTTP 请求发送通知到 Bark 服务器。Bark 是一个开源的 iOS 推送通知服务，可以将通知推送到 iOS 设备。

**参考实现**: [Bark 官方文档](https://github.com/Finb/Bark)

## 配置

### 1. 配置文件设置

在 `application.properties` 中配置 Bark 服务：

```properties
# Bark 通知服务配置
# Bark服务器地址
airopscat.bark.url=https://push.fun90.com
# Bark设备密钥
airopscat.bark.device-key=YOUR_DEVICE_KEY
```

### 2. 获取 Bark Device Key

1. 在 iOS 设备上安装 Bark 应用
2. 打开应用，复制设备密钥
3. 将密钥配置到 `application.properties` 中

## API 接口

### 1. 发送简单通知（GET方式）

**POST** `/api/admin/bark/notify`

**参数：**
- `title` (String): 通知标题
- `body` (String): 通知内容

**示例：**
```bash
curl -X POST "http://localhost:8080/api/admin/bark/notify" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "title=测试通知&body=这是一条测试通知"
```

### 2. 发送自定义通知（GET方式）

**POST** `/api/admin/bark/notify/custom`

**请求体：**
```json
{
  "deviceKey": "YOUR_DEVICE_KEY",
  "title": "自定义通知",
  "body": "通知内容",
  "icon": "https://example.com/icon.png",
  "sound": "alarm",
  "url": "https://example.com",
  "group": "AirOpsCat",
  "autoCopy": true,
  "copy": "复制的内容",
  "level": "active",
  "badge": 1,
  "isArchive": false,
  "category": "notification",
  "threadId": "thread-123",
  "priority": 10,
  "timeout": 30,
  "actionable": true,
  "actions": "action1,action2"
}
```

### 3. 发送POST请求通知

**POST** `/api/admin/bark/notify/post`

**请求体：**
```json
{
  "deviceKey": "YOUR_DEVICE_KEY",
  "title": "POST通知",
  "body": "通知内容",
  "icon": "https://example.com/icon.png",
  "sound": "alarm",
  "url": "https://example.com",
  "group": "AirOpsCat",
  "autoCopy": true,
  "copy": "复制的内容",
  "level": "active",
  "badge": 1,
  "isArchive": false,
  "category": "notification",
  "threadId": "thread-123",
  "priority": 10,
  "timeout": 30,
  "actionable": true,
  "actions": "action1,action2"
}
```

### 4. 发送警告通知

**POST** `/api/admin/bark/notify/warning`

**参数：**
- `title` (String): 通知标题
- `body` (String): 通知内容

### 5. 发送错误通知

**POST** `/api/admin/bark/notify/error`

**参数：**
- `title` (String): 通知标题
- `body` (String): 通知内容

### 6. 发送信息通知

**POST** `/api/admin/bark/notify/info`

**参数：**
- `title` (String): 通知标题
- `body` (String): 通知内容

### 7. 获取配置状态

**GET** `/api/admin/bark/status`

**响应：**
```json
{
  "configured": true,
  "barkUrl": "https://push.fun90.com",
  "deviceKey": "YOUR_DEVI...",
  "message": "Bark已配置",
  "supportedMethods": ["GET", "POST"]
}
```

### 8. 测试连接（GET方式）

**POST** `/api/admin/bark/test`

**响应：**
```json
{
  "success": true,
  "message": "Bark连接测试成功",
  "method": "GET"
}
```

### 9. 测试连接（POST方式）

**POST** `/api/admin/bark/test/post`

**响应：**
```json
{
  "success": true,
  "message": "Bark POST连接测试成功",
  "method": "POST"
}
```

## 服务类使用

### 1. 注入 BarkService

```java
@Autowired
private BarkService barkService;
```

### 2. 发送简单通知（GET方式）

```java
boolean success = barkService.sendNotification("标题", "内容");
```

### 3. 发送自定义通知（GET方式）

```java
BarkNotificationDto notification = BarkNotificationDto.builder()
    .title("自定义通知")
    .body("通知内容")
    .icon("https://example.com/icon.png")
    .sound("alarm")
    .group("AirOpsCat")
    .level("active")
    .category("notification")
    .priority(10)
    .build();

boolean success = barkService.sendNotification(notification);
```

### 4. 发送POST请求通知

```java
BarkNotificationDto notification = BarkNotificationDto.builder()
    .title("POST通知")
    .body("通知内容")
    .icon("https://example.com/icon.png")
    .sound("alarm")
    .group("AirOpsCat")
    .level("active")
    .build();

boolean success = barkService.sendNotificationPost(notification);
```

### 5. 发送不同类型的通知

```java
// 发送警告通知
barkService.sendWarningNotification("警告", "这是一个警告");

// 发送错误通知
barkService.sendErrorNotification("错误", "这是一个错误");

// 发送信息通知
barkService.sendInfoNotification("信息", "这是一条信息");
```

### 6. 检查配置状态

```java
boolean configured = barkService.isBarkConfigured();
String barkUrl = barkService.getBarkUrl();
String deviceKey = barkService.getDeviceKey();
```

## 通知参数说明

### BarkNotificationDto 字段

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| deviceKey | String | 设备密钥 | "YOUR_DEVICE_KEY" |
| title | String | 通知标题 | "系统通知" |
| body | String | 通知内容 | "服务器重启完成" |
| icon | String | 通知图标URL | "https://example.com/icon.png" |
| sound | String | 通知声音 | "alarm", "system" |
| url | String | 点击跳转URL | "https://example.com" |
| group | String | 通知分组 | "AirOpsCat" |
| autoCopy | Boolean | 自动复制 | true |
| copy | String | 复制内容 | "复制的内容" |
| level | String | 通知级别 | "active", "timeSensitive", "passive" |
| badge | Integer | 徽章数量 | 1 |
| isArchive | Boolean | 是否静默 | false |
| category | String | 通知分类 | "notification" |
| threadId | String | 通知线程ID | "thread-123" |
| priority | Integer | 通知优先级 | 10 |
| timeout | Integer | 通知超时时间（秒） | 30 |
| actionable | Boolean | 通知是否可操作 | true |
| actions | String | 通知操作按钮 | "action1,action2" |

### 通知级别说明

- `active`: 主动通知，需要用户立即处理
- `timeSensitive`: 时间敏感通知，需要及时处理
- `passive`: 被动通知，仅供参考

## 技术实现

### 1. 请求方式
- 支持 **GET** 和 **POST** 两种请求方式
- 参考 [Bark 官方文档](https://github.com/Finb/Bark) 实现

### 2. URL结构（GET方式）
根据 Bark 官方文档，支持以下 URL 结构：

- `/:key/:body` - 只发送内容
- `/:key/:title/:body` - 发送标题和内容
- `/:key/:title/:subtitle/:body` - 发送标题、副标题和内容

**示例：**
```
https://push.fun90.com/8TdAZNri6RV5kWu8dskjQb/测试标题/测试内容?sound=alarm&group=AirOpsCat
```

### 3. POST请求格式
```json
{
  "deviceKey": "YOUR_DEVICE_KEY",
  "title": "通知标题",
  "body": "通知内容",
  "icon": "https://example.com/icon.png",
  "sound": "alarm",
  "url": "https://example.com",
  "group": "AirOpsCat",
  "autoCopy": true,
  "copy": "复制的内容",
  "level": "active",
  "badge": 1,
  "isArchive": false,
  "category": "notification",
  "threadId": "thread-123",
  "priority": 10,
  "timeout": 30,
  "actionable": true,
  "actions": "action1,action2"
}
```

### 4. 响应格式
```json
{
  "code": 200,
  "message": "success"
}
```

## 集成示例

### 1. 在服务异常时发送通知

```java
@Service
public class SomeService {
    
    @Autowired
    private BarkService barkService;
    
    public void someMethod() {
        try {
            // 执行业务逻辑
        } catch (Exception e) {
            // 发送错误通知
            barkService.sendErrorNotification(
                "服务异常", 
                "发生异常: " + e.getMessage()
            );
            throw e;
        }
    }
}
```

### 2. 在定时任务中发送通知

```java
@Component
public class ScheduledTask {
    
    @Autowired
    private BarkService barkService;
    
    @Scheduled(fixedRate = 3600000) // 每小时执行一次
    public void checkSystemStatus() {
        // 检查系统状态
        if (systemHasIssue()) {
            barkService.sendWarningNotification(
                "系统警告", 
                "检测到系统异常，请及时处理"
            );
        }
    }
}
```

### 3. 在用户操作后发送通知

```java
@RestController
public class UserController {
    
    @Autowired
    private BarkService barkService;
    
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody UserRequest request) {
        // 创建用户
        User user = userService.createUser(request);
        
        // 发送通知
        barkService.sendInfoNotification(
            "用户创建", 
            "新用户 " + user.getUsername() + " 已创建"
        );
        
        return ResponseEntity.ok(user);
    }
}
```

## 注意事项

1. **配置检查**: 使用前请确保已正确配置 Bark URL 和设备密钥
2. **网络连接**: 确保服务器能够访问 Bark API
3. **错误处理**: 服务会捕获异常并记录日志，但不会抛出异常
4. **性能考虑**: 通知发送是同步的，不会阻塞主业务流程
5. **安全性**: 请妥善保管设备密钥，避免泄露
6. **URL编码**: GET 请求中的中文内容会自动进行 URL 编码

## 故障排除

### 1. 通知发送失败

- 检查 Bark URL 和设备密钥是否正确配置
- 确认网络连接正常
- 查看应用日志获取详细错误信息
- 尝试使用 POST 方式发送通知

### 2. 通知未收到

- 确认 iOS 设备上的 Bark 应用正常运行
- 检查设备密钥是否正确
- 确认通知权限已开启

### 3. 配置问题

- 检查 `application.properties` 中的配置
- 重启应用使配置生效
- 使用 `/api/admin/bark/status` 接口检查配置状态

### 4. URL结构问题

- 确保 URL 结构符合 Bark 官方规范
- 检查 URL 编码是否正确
- 验证查询参数格式 