# 订阅服务错误处理优化

## 概述

本次优化主要改进了 `SubscriptionService` 和 `SubscriptionController` 的错误处理机制，从简单的返回 `null` 改为返回详细的错误信息。

## 主要改进

### 1. 新增通用响应DTO

创建了 `ApiResponseDto<T>` 类来统一处理响应结果，使用泛型设计使其适用于各种接口：

```java
public class ApiResponseDto<T> {
    private boolean success;
    private String message;
    private T data;
    private Long timestamp;
    
    public static <T> ApiResponseDto<T> success(T data)
    public static <T> ApiResponseDto<T> success(T data, String message)
    public static <T> ApiResponseDto<T> error(String message)
    public static <T> ApiResponseDto<T> error(String message, T data)
}
```

### 2. 优化 SubscriptionService.generateSubscription 方法

**之前的处理方式：**
- 参数验证失败：返回 `null`
- 账户不存在：返回 `null`
- 账户被禁用：返回 `null`
- 没有可用节点：返回 `null`

**优化后的处理方式：**
- 参数验证失败：返回 `ApiResponseDto.error("认证码不能为空")`
- 账户不存在：返回 `ApiResponseDto.error("无效的认证码，账户不存在")`
- 账户被禁用：返回 `ApiResponseDto.error("账户已被禁用，请联系管理员")`
- 账户过期：返回 `ApiResponseDto.error("账户已过期，请续费后重试")`
- 没有可用节点：返回 `ApiResponseDto.error("当前账户没有可用的节点，请联系管理员")`
- 没有活跃节点：返回 `ApiResponseDto.error("当前没有可用的活跃节点，请稍后重试")`
- 不支持的应用类型：返回 `ApiResponseDto.error("生成配置文件失败，不支持的应用类型: " + appName)`
- 成功时：返回 `ApiResponseDto.success(subscriptionDto)`

### 3. 优化 SubscriptionService.getNodes 方法

**之前的处理方式：**
- 各种错误情况都返回空字符串 `""`

**优化后的处理方式：**
- 账户不存在：返回"错误: 无效的认证码，账户不存在"
- 账户被禁用：返回"错误: 账户已被禁用，请联系管理员"
- 账户过期：返回"错误: 账户已过期，请续费后重试"
- 没有可用节点：返回"错误: 当前账户没有可用的节点，请联系管理员"
- 没有活跃节点：返回"错误: 当前没有可用的活跃节点，请稍后重试"
- 模板不存在：返回"错误: 找不到对应的节点模板: {appType}"

### 4. 优化 SubscriptionService.getRule 方法

**之前的处理方式：**
- 参数为空或模板不存在：返回 `null`

**优化后的处理方式：**
- 应用类型为空：返回"错误: 应用类型不能为空"
- 规则名称为空：返回"错误: 规则名称不能为空"
- 规则文件不存在：返回"错误: 找不到对应的规则文件: {templateName}"

### 5. 优化 SubscriptionController

**之前的处理方式：**
- 服务返回 `null` 时：返回 `404 Not Found`
- 异常时：返回 `400 Bad Request` 但无具体错误信息

**优化后的处理方式：**
- 服务返回 `ApiResponseDto` 错误响应时：返回 `400 Bad Request` 并包含具体错误信息
- 配置文件生成失败时：返回"错误: 生成配置文件失败"
- 系统异常时：返回"错误: 系统内部错误，请联系管理员"

## 错误信息示例

### 订阅配置接口 (`/subscribe/config/{authCode}/{osName}/{appName}`)

**成功响应：**
```
HTTP 200 OK
Content-Type: text/plain
[配置文件内容]
```

**错误响应：**
```
HTTP 400 Bad Request
Content-Type: text/plain
错误: 账户已过期，请续费后重试
```

### 节点列表接口 (`/subscribe/nodes/{authCode}/{appType}`)

**成功响应：**
```
HTTP 200 OK
Content-Type: text/plain
[节点列表内容]
```

**错误响应：**
```
HTTP 200 OK
Content-Type: text/plain
错误: 当前没有可用的活跃节点，请稍后重试
```

### 规则文件接口 (`/subscribe/rules/{appType}/{ruleName}`)

**成功响应：**
```
HTTP 200 OK
Content-Type: text/plain
[规则文件内容]
```

**错误响应：**
```
HTTP 200 OK
Content-Type: text/plain
错误: 找不到对应的规则文件: rules/clash/mydirect.yaml
```

## 优势

1. **用户体验提升**：用户能够清楚地知道问题所在，而不是看到空白页面或下载空文件
2. **调试便利性**：开发人员能够快速定位问题
3. **维护性**：统一的错误处理机制，便于后续维护和扩展
4. **国际化友好**：错误信息可以轻松替换为多语言版本
5. **泛型设计**：`ApiResponseDto<T>` 可以适用于各种接口，提供类型安全
6. **扩展性**：可以轻松添加新的字段和功能，如时间戳等

## 注意事项

1. 所有错误信息都使用中文，便于中国用户理解
2. 错误信息简洁明了，避免技术术语
3. 保持了原有的HTTP状态码语义
4. 向后兼容，不会影响现有的正常使用 