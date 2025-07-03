# 定时任务说明

## 概述

AirOpsCat 系统包含一个定时任务服务，用于自动检查和维护系统状态。

## 定时任务列表

### 1. 过期账户节点重新部署任务

**执行时间**: 每天凌晨 5:00  
**Cron 表达式**: `0 0 5 * * ?`

#### 功能描述
- 自动查询未禁用但已过期的账户列表
- 找出这些账户关联的所有节点（通过标签关联）
- 批量重新部署这些节点
- 记录部署结果和错误信息

#### 执行流程
1. 查询条件：`disabled = 0 AND toDate IS NOT NULL AND toDate < now`
2. 通过 `TagService.getAvailableNodesByAccount()` 获取账户关联的节点
3. 使用 `NodeDeploymentService.deployNodes()` 批量重新部署节点
4. 统计部署结果（成功/失败数量）
5. 记录详细的错误日志

#### 日志输出示例
```
2024-01-01 05:00:00.000 INFO  - 开始执行定时任务：检查过期账户并重新部署节点
2024-01-01 05:00:00.100 INFO  - 找到 3 个未禁用但已过期的账户
2024-01-01 05:00:00.200 INFO  - 需要重新部署的节点数量: 5
2024-01-01 05:00:05.300 INFO  - 节点重新部署完成 - 成功: 4, 失败: 1
2024-01-01 05:00:05.301 ERROR - 节点 123 重新部署失败: SSH连接超时
2024-01-01 05:00:05.302 INFO  - 定时任务执行完成
```

## 配置说明

### 启用定时任务
在主应用类 `AirOpsCatApplication.java` 中添加 `@EnableScheduling` 注解：

```java
@SpringBootApplication
@EnableScheduling
public class AirOpsCatApplication {
    // ...
}
```

### 修改执行时间
如需修改定时任务的执行时间，可以在 `ScheduledTaskService.java` 中修改 `@Scheduled` 注解的 cron 表达式：

```java
@Scheduled(cron = "0 0 5 * * ?")  // 每天凌晨5点
```

### Cron 表达式说明
- `0` - 秒 (0-59)
- `0` - 分钟 (0-59)  
- `5` - 小时 (0-23)
- `*` - 日 (1-31)
- `*` - 月 (1-12)
- `?` - 星期 (1-7 或 SUN-SAT)

## 监控和维护

### 查看任务执行状态
定时任务的执行状态会记录在应用日志中，可以通过以下方式查看：

1. **应用日志文件**: 查看应用的标准输出或日志文件
2. **日志级别**: 确保日志级别设置为 INFO 或更低以查看详细日志

### 手动触发任务
如需手动触发定时任务进行测试，可以通过以下方式：

1. **直接调用方法**:
```java
@Autowired
private ScheduledTaskService scheduledTaskService;

// 手动执行任务
scheduledTaskService.checkExpiredAccountsAndRedeployNodes();
```

2. **通过 REST API** (需要添加相应的控制器方法)

### 故障排查

#### 常见问题
1. **任务未执行**: 检查 `@EnableScheduling` 注解是否正确添加
2. **部署失败**: 检查 SSH 连接配置和服务器状态
3. **数据库查询异常**: 检查数据库连接和表结构

#### 调试建议
1. 临时修改 cron 表达式为更频繁的执行（如每分钟）进行测试
2. 添加更详细的日志输出来跟踪执行过程
3. 使用单元测试验证业务逻辑

## 扩展功能

### 添加新的定时任务
1. 在 `ScheduledTaskService` 中添加新的方法
2. 使用 `@Scheduled` 注解配置执行时间
3. 实现具体的业务逻辑
4. 添加相应的单元测试

### 示例：添加清理任务
```java
@Scheduled(cron = "0 0 2 * * ?")  // 每天凌晨2点
public void cleanupOldLogs() {
    log.info("开始执行日志清理任务");
    // 实现清理逻辑
    log.info("日志清理任务完成");
}
```

## 注意事项

1. **时区设置**: 系统使用 `Asia/Shanghai` 时区，cron 表达式基于此时区
2. **并发控制**: 默认情况下，Spring 的定时任务不会并发执行同一个任务
3. **异常处理**: 任务中的异常会被捕获并记录，不会影响其他任务的执行
4. **资源管理**: 长时间运行的任务需要注意内存和连接池的使用 