# AirOpsCat 更新通知实现方案

## 概述

本文档描述了将原有的 `InternalConstant` 类改造为 Spring Boot 启动时执行的更新通知方案。

## 原始代码分析

原始代码使用 `@PostConstruct` 注解在组件初始化时执行版本检查：

```java
@Component
@PropertySource("internal.properties")
@Getter
@Setter
@Slf4j
public class InternalConstant {
    @Autowired
    RestTemplate restTemplate;
    @Value("${app.githubURL}")
    String githubURL;
    @Value("${app.version}")
    private String version;

    @PostConstruct
    public void init() {
        new Thread(() -> {
            // 版本检查逻辑
        }).start();
    }
}
```

## 改造方案

### Service 实现 CommandLineRunner

创建 `UpdateNotificationService` 类，实现 `CommandLineRunner` 接口：

```java
@Service
@PropertySource("internal.properties")
@Slf4j
public class UpdateNotificationService implements CommandLineRunner {
    
    @Autowired
    private RestTemplate restTemplate;
    
    @Value("${app.githubURL}")
    private String githubURL;
    
    @Value("${app.version}")
    private String currentVersion;

    @Override
    public void run(String... args) throws Exception {
        // 异步执行版本检查
        new Thread(() -> {
            try {
                checkForUpdates();
            } catch (Exception e) {
                log.warn("版本检查失败: {}", e.getMessage());
            }
        }).start();
    }
    
    private void checkForUpdates() {
        // 版本检查逻辑
    }
}
```

## 主要改进

### 1. 依赖替换
- 将 `com.alibaba.fastjson2.JSONObject` 替换为 `com.fasterxml.jackson.databind.JsonNode`
- 使用 Spring Boot 内置的 Jackson 库，避免额外依赖

### 2. 工具类创建
创建 `VersionUtil` 类提供版本比较功能：

```java
public class VersionUtil {
    public static int compareVersion(String version1, String version2) {
        // 版本比较逻辑
    }
}
```

### 3. RestTemplate 配置
在 `WebConfig` 中添加 `RestTemplate` Bean：

```java
@Bean
public RestTemplate restTemplate() {
    return new RestTemplate();
}
```

## 优势

1. **Spring Boot 原生支持**：使用 `CommandLineRunner` 接口，符合 Spring Boot 最佳实践
2. **异步执行**：不阻塞应用启动过程
3. **错误处理**：完善的异常处理机制
4. **配置灵活**：支持从 `internal.properties` 读取配置
5. **日志记录**：详细的日志输出，便于调试

## 配置说明

### internal.properties
```properties
app.version=@project.version@
app.githubURL=https://api.github.com/repos/fun90/AirOpsCat/releases/latest
```

### 版本比较逻辑
- 返回 0：版本相同
- 返回 -1：当前版本低于最新版本
- 返回 1：当前版本高于最新版本（开发版本）

## 使用建议

1. **Service 方式**：更符合业务逻辑封装原则
2. **生产环境**：建议添加网络超时配置和重试机制
3. **日志级别**：可根据需要调整日志输出级别
4. **更新频率**：当前为启动时检查，如需定期检查可结合 `@Scheduled` 注解

## 文件结构

```
src/main/java/com/fun90/airopscat/
├── config/
│   └── WebConfig.java (添加 RestTemplate Bean)
├── service/
│   └── UpdateNotificationService.java (更新通知服务)
└── utils/
    └── VersionUtil.java (版本比较工具)
``` 