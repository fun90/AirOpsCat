# ApiResponseDto 使用示例

## 概述

`ApiResponseDto<T>` 是一个通用的响应数据传输对象，使用泛型设计，可以适用于各种API接口的响应。

## 基本结构

```java
public class ApiResponseDto<T> {
    private boolean success;    // 操作是否成功
    private String message;     // 响应消息
    private T data;            // 响应数据（泛型）
    private Long timestamp;    // 时间戳
}
```

## 静态工厂方法

### 成功响应
```java
// 基本成功响应
ApiResponseDto<UserDto> response = ApiResponseDto.success(userDto);

// 带自定义消息的成功响应
ApiResponseDto<UserDto> response = ApiResponseDto.success(userDto, "用户创建成功");
```

### 错误响应
```java
// 基本错误响应
ApiResponseDto<UserDto> response = ApiResponseDto.error("用户不存在");

// 带数据的错误响应（用于部分成功的情况）
ApiResponseDto<UserDto> response = ApiResponseDto.error("部分数据验证失败", partialData);
```

## 使用示例

### 1. 用户管理接口

```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    
    @GetMapping("/{id}")
    public ApiResponseDto<UserDto> getUser(@PathVariable Long id) {
        try {
            UserDto user = userService.getUserById(id);
            if (user != null) {
                return ApiResponseDto.success(user);
            } else {
                return ApiResponseDto.error("用户不存在");
            }
        } catch (Exception e) {
            return ApiResponseDto.error("系统错误: " + e.getMessage());
        }
    }
    
    @PostMapping
    public ApiResponseDto<UserDto> createUser(@RequestBody UserRequest request) {
        try {
            UserDto newUser = userService.createUser(request);
            return ApiResponseDto.success(newUser, "用户创建成功");
        } catch (ValidationException e) {
            return ApiResponseDto.error("数据验证失败: " + e.getMessage());
        } catch (Exception e) {
            return ApiResponseDto.error("创建用户失败: " + e.getMessage());
        }
    }
}
```

### 2. 节点管理接口

```java
@RestController
@RequestMapping("/api/nodes")
public class NodeController {
    
    @GetMapping
    public ApiResponseDto<List<NodeDto>> getNodes() {
        try {
            List<NodeDto> nodes = nodeService.getAllNodes();
            return ApiResponseDto.success(nodes);
        } catch (Exception e) {
            return ApiResponseDto.error("获取节点列表失败: " + e.getMessage());
        }
    }
    
    @PostMapping("/deploy/{id}")
    public ApiResponseDto<DeploymentResult> deployNode(@PathVariable Long id) {
        try {
            DeploymentResult result = nodeService.deployNode(id);
            if (result.isSuccess()) {
                return ApiResponseDto.success(result, "节点部署成功");
            } else {
                return ApiResponseDto.error("节点部署失败: " + result.getMessage(), result);
            }
        } catch (Exception e) {
            return ApiResponseDto.error("部署过程中发生错误: " + e.getMessage());
        }
    }
}
```

### 3. 订阅服务接口（原有功能）

```java
@Service
public class SubscriptionService {
    
    public ApiResponseDto<SubscrptionDto> generateSubscription(String authCode, String osName, String appName) {
        // 验证参数
        if (!StringUtils.hasText(authCode)) {
            return ApiResponseDto.error("认证码不能为空");
        }
        
        // 查找账户
        Optional<Account> account = accountRepository.findByAuthCode(authCode);
        if (account.isEmpty()) {
            return ApiResponseDto.error("无效的认证码，账户不存在");
        }
        
        // 生成订阅内容
        SubscrptionDto subscription = generateSubscriptionContent(account.get(), osName, appName);
        return ApiResponseDto.success(subscription);
    }
}
```

### 4. 批量操作接口

```java
@RestController
@RequestMapping("/api/batch")
public class BatchController {
    
    @PostMapping("/users/disable")
    public ApiResponseDto<BatchResult> disableUsers(@RequestBody List<Long> userIds) {
        try {
            BatchResult result = userService.disableUsers(userIds);
            if (result.getSuccessCount() == userIds.size()) {
                return ApiResponseDto.success(result, "所有用户已禁用");
            } else {
                return ApiResponseDto.error(
                    String.format("部分操作失败，成功: %d, 失败: %d", 
                        result.getSuccessCount(), result.getFailureCount()),
                    result
                );
            }
        } catch (Exception e) {
            return ApiResponseDto.error("批量操作失败: " + e.getMessage());
        }
    }
}
```

## 响应格式示例

### 成功响应
```json
{
    "success": true,
    "message": "操作成功",
    "data": {
        "id": 1,
        "name": "张三",
        "email": "zhangsan@example.com"
    },
    "timestamp": 1640995200000
}
```

### 错误响应
```json
{
    "success": false,
    "message": "用户不存在",
    "data": null,
    "timestamp": 1640995200000
}
```

### 部分成功的错误响应
```json
{
    "success": false,
    "message": "部分数据验证失败",
    "data": {
        "validItems": [1, 2, 3],
        "invalidItems": [4, 5],
        "errors": ["ID 4: 邮箱格式错误", "ID 5: 用户名已存在"]
    },
    "timestamp": 1640995200000
}
```

## 优势

1. **类型安全**：使用泛型确保编译时类型检查
2. **统一格式**：所有API响应使用相同的结构
3. **灵活性**：支持各种数据类型
4. **向后兼容**：可以轻松替换现有的响应格式
5. **易于扩展**：可以添加更多字段而不破坏现有代码

## 最佳实践

1. **始终使用泛型**：明确指定返回的数据类型
2. **提供有意义的错误消息**：帮助前端和用户理解问题
3. **在部分成功时使用data字段**：提供详细的错误信息
4. **保持一致性**：在整个项目中统一使用相同的响应格式
5. **记录时间戳**：便于调试和日志分析 