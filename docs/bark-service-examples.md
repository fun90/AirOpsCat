# BarkService 使用示例

## 基本使用

### 1. 在服务中注入 BarkService

```java
@Service
public class SomeService {
    
    @Autowired
    private BarkService barkService;
    
    // 其他业务逻辑...
}
```

### 2. 发送简单通知

```java
// 发送简单通知
boolean success = barkService.sendNotification("系统通知", "服务器启动完成");
if (success) {
    log.info("通知发送成功");
} else {
    log.warn("通知发送失败");
}
```

### 3. 发送不同类型的通知

```java
// 发送信息通知
barkService.sendInfoNotification("用户登录", "用户 admin 已登录系统");

// 发送警告通知
barkService.sendWarningNotification("系统警告", "CPU 使用率超过 80%");

// 发送错误通知
barkService.sendErrorNotification("系统错误", "数据库连接失败");
```

### 4. 发送自定义通知

```java
BarkNotificationDto notification = BarkNotificationDto.builder()
    .title("自定义通知")
    .body("这是一条自定义通知")
    .icon("https://example.com/icon.png")
    .sound("alarm")
    .url("https://example.com")
    .group("AirOpsCat")
    .level("active")
    .badge(1)
    .build();

boolean success = barkService.sendNotification(notification);
```

## 实际应用场景

### 1. 系统监控通知

```java
@Component
public class SystemMonitorService {
    
    @Autowired
    private BarkService barkService;
    
    @Scheduled(fixedRate = 300000) // 每5分钟检查一次
    public void checkSystemHealth() {
        double cpuUsage = getCpuUsage();
        double memoryUsage = getMemoryUsage();
        
        if (cpuUsage > 90) {
            barkService.sendErrorNotification(
                "系统告警", 
                String.format("CPU使用率过高: %.1f%%", cpuUsage)
            );
        } else if (cpuUsage > 80) {
            barkService.sendWarningNotification(
                "系统警告", 
                String.format("CPU使用率较高: %.1f%%", cpuUsage)
            );
        }
        
        if (memoryUsage > 90) {
            barkService.sendErrorNotification(
                "系统告警", 
                String.format("内存使用率过高: %.1f%%", memoryUsage)
            );
        }
    }
}
```

### 2. 用户操作通知

```java
@RestController
public class UserController {
    
    @Autowired
    private BarkService barkService;
    
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody UserRequest request) {
        try {
            User user = userService.createUser(request);
            
            // 发送成功通知
            barkService.sendInfoNotification(
                "用户创建", 
                String.format("新用户 %s 已创建", user.getUsername())
            );
            
            return ResponseEntity.ok(user);
        } catch (Exception e) {
            // 发送错误通知
            barkService.sendErrorNotification(
                "用户创建失败", 
                String.format("创建用户失败: %s", e.getMessage())
            );
            throw e;
        }
    }
    
    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        try {
            userService.deleteUser(id);
            
            barkService.sendWarningNotification(
                "用户删除", 
                String.format("用户 ID %d 已被删除", id)
            );
            
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            barkService.sendErrorNotification(
                "用户删除失败", 
                String.format("删除用户失败: %s", e.getMessage())
            );
            throw e;
        }
    }
}
```

### 3. 服务器管理通知

```java
@Service
public class ServerManagementService {
    
    @Autowired
    private BarkService barkService;
    
    public void deployServer(Server server) {
        try {
            // 部署服务器逻辑
            serverService.deploy(server);
            
            barkService.sendInfoNotification(
                "服务器部署", 
                String.format("服务器 %s 部署成功", server.getName())
            );
        } catch (Exception e) {
            barkService.sendErrorNotification(
                "服务器部署失败", 
                String.format("服务器 %s 部署失败: %s", server.getName(), e.getMessage())
            );
            throw e;
        }
    }
    
    public void checkServerStatus() {
        List<Server> servers = serverService.getAllServers();
        
        for (Server server : servers) {
            if (!serverService.isOnline(server)) {
                barkService.sendErrorNotification(
                    "服务器离线", 
                    String.format("服务器 %s 已离线", server.getName())
                );
            }
        }
    }
}
```

### 4. 定时任务通知

```java
@Component
public class ScheduledTasks {
    
    @Autowired
    private BarkService barkService;
    
    @Scheduled(cron = "0 0 9 * * ?") // 每天早上9点
    public void dailyReport() {
        try {
            // 生成日报
            DailyReport report = reportService.generateDailyReport();
            
            barkService.sendInfoNotification(
                "日报生成", 
                String.format("日报已生成，共处理 %d 条记录", report.getRecordCount())
            );
        } catch (Exception e) {
            barkService.sendErrorNotification(
                "日报生成失败", 
                String.format("生成日报失败: %s", e.getMessage())
            );
        }
    }
    
    @Scheduled(cron = "0 0 2 * * ?") // 每天凌晨2点
    public void backupDatabase() {
        try {
            // 数据库备份
            backupService.backup();
            
            barkService.sendInfoNotification(
                "数据库备份", 
                "数据库备份完成"
            );
        } catch (Exception e) {
            barkService.sendErrorNotification(
                "数据库备份失败", 
                String.format("数据库备份失败: %s", e.getMessage())
            );
        }
    }
}
```

### 5. 异常处理通知

```java
@ControllerAdvice
public class GlobalExceptionHandler {
    
    @Autowired
    private BarkService barkService;
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception e) {
        // 发送错误通知
        barkService.sendErrorNotification(
            "系统异常", 
            String.format("发生未处理的异常: %s", e.getMessage())
        );
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body("系统内部错误");
    }
    
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<?> handleValidationException(ValidationException e) {
        // 发送警告通知
        barkService.sendWarningNotification(
            "数据验证失败", 
            String.format("数据验证失败: %s", e.getMessage())
        );
        
        return ResponseEntity.badRequest().body("数据验证失败");
    }
}
```

### 6. 安全事件通知

```java
@Component
public class SecurityEventService {
    
    @Autowired
    private BarkService barkService;
    
    public void handleLoginAttempt(String username, String ip, boolean success) {
        if (!success) {
            barkService.sendWarningNotification(
                "登录失败", 
                String.format("用户 %s 从 IP %s 登录失败", username, ip)
            );
        }
    }
    
    public void handleSuspiciousActivity(String activity, String details) {
        barkService.sendErrorNotification(
            "可疑活动", 
            String.format("检测到可疑活动: %s - %s", activity, details)
        );
    }
}
```

## 配置示例

### application.properties

```properties
# Bark 通知服务配置
airopscat.bark.url=https://api.day.app/YOUR_DEVICE_KEY

# 可选：配置通知级别
logging.level.com.fun90.airopscat.service.BarkService=INFO
```

### 环境变量配置

```bash
# 设置环境变量
export AIROPS_BARK_URL="https://api.day.app/YOUR_DEVICE_KEY"

# 或者在 application.properties 中使用
airopscat.bark.url=${AIROPS_BARK_URL:}
```

## 最佳实践

### 1. 通知级别选择

- **信息通知 (passive)**: 用于日常操作、成功事件
- **警告通知 (timeSensitive)**: 用于需要注意但不紧急的情况
- **错误通知 (active)**: 用于需要立即处理的错误和异常

### 2. 通知内容规范

- 标题简洁明了，不超过20个字符
- 内容包含关键信息，避免过长
- 使用格式化字符串提高可读性

### 3. 错误处理

```java
// 检查配置状态
if (!barkService.isBarkConfigured()) {
    log.warn("Bark 未配置，跳过通知发送");
    return;
}

// 处理发送失败
boolean success = barkService.sendNotification(title, body);
if (!success) {
    log.error("通知发送失败: {}", title);
    // 可以尝试其他通知方式或记录到数据库
}
```

### 4. 性能考虑

- 通知发送是异步的，不会阻塞主业务流程
- 避免在循环中频繁发送通知
- 考虑使用通知聚合，避免通知轰炸

### 5. 测试

```java
@Test
void testNotificationSending() {
    // 测试配置状态
    assertTrue(barkService.isBarkConfigured());
    
    // 测试通知发送
    boolean success = barkService.sendNotification("测试", "测试内容");
    assertTrue(success);
}
``` 