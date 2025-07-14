# AirOpsCat Spring Boot 3.4.4 到 Quarkus 3.24.3 迁移计划

## 项目概览

AirOpsCat 是一个基于 Spring Boot 3.4.4、Java 21 的服务器管理系统，提供代理服务管理功能。本文档提供从 Spring Boot 迁移到 Quarkus 3.24.3 的完整迁移计划。

### 当前技术栈分析

- **框架**: Spring Boot 3.4.4 + Spring Security 6.x
- **数据层**: Spring Data JPA + Hibernate + SQLite
- **模板引擎**: Thymeleaf + Tabler UI
- **构建工具**: Maven + GraalVM Native Image
- **其他依赖**: MapStruct, Lombok, Apache Commons, JSch

## 迁移策略

采用**分阶段渐进式迁移**方式，先搭建 Quarkus 框架基础，再逐模块迁移：

1. **阶段一**: 框架配置迁移
2. **阶段二**: 核心模块迁移
3. **阶段三**: 安全与模板迁移
4. **阶段四**: 测试与优化

## 阶段一：框架配置迁移

### 1.1 项目初始化

```bash
# 创建新的 Quarkus 项目
mvn io.quarkus:quarkus-maven-plugin:3.24.3:create \
  -DprojectGroupId=com.fun90 \
  -DprojectArtifactId=airopscat-quarkus \
  -DprojectVersion=1.0.2 \
  -DjavaVersion=21 \
  -Dextensions="resteasy-reactive,resteasy-reactive-jackson,hibernate-orm,hibernate-validator,jdbc-sqlite,security,qute,spring-di,spring-web,spring-data-jpa,spring-security"
```

### 1.2 依赖迁移映射

| Spring Boot 依赖 | Quarkus 扩展 | 说明 |
|---|---|---|
| `spring-boot-starter-web` | `quarkus-resteasy-reactive` | REST API |
| `spring-boot-starter-data-jpa` | `quarkus-hibernate-orm-panache` | 数据持久化 |
| `spring-boot-starter-security` | `quarkus-security-jpa` + `quarkus-elytron-security-properties-file` | 安全框架 |
| `spring-boot-starter-thymeleaf` | `quarkus-qute` | 模板引擎 |
| `spring-boot-starter-validation` | `quarkus-hibernate-validator` | 数据验证 |
| `sqlite-jdbc` | `quarkus-jdbc-sqlite` | SQLite 驱动 |
| `jsch` | `quarkus-jsch` | SSH 客户端库 |
| `lombok` | `lombok` (保持不变) | 代码生成 |
| `mapstruct` | `mapstruct` (保持不变) | 对象映射 |

### 1.3 新 pom.xml 配置

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.fun90</groupId>
    <artifactId>airopscat-quarkus</artifactId>
    <version>1.0.2</version>

    <properties>
        <compiler-plugin.version>3.11.0</compiler-plugin.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <quarkus.platform.version>3.24.3</quarkus.platform.version>
        <mapstruct.version>1.5.5.Final</mapstruct.version>
        <lombok-mapstruct-binding.version>0.2.0</lombok-mapstruct-binding.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.quarkus.platform</groupId>
                <artifactId>quarkus-bom</artifactId>
                <version>${quarkus.platform.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Quarkus 核心扩展 -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-resteasy-reactive</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-resteasy-reactive-jackson</artifactId>
        </dependency>

        <!-- 数据库和 JPA -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-hibernate-orm-panache</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jdbc-sqlite</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-hibernate-validator</artifactId>
        </dependency>

        <!-- 安全 -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-security-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-elytron-security-properties-file</artifactId>
        </dependency>

        <!-- SSH 客户端 -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jsch</artifactId>
        </dependency>

        <!-- 模板引擎 -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-qute</artifactId>
        </dependency>

        <!-- 工具库 (保持不变) -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>1.18.30</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct</artifactId>
            <version>${mapstruct.version}</version>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-lang3</artifactId>
            <version>3.13.0</version>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-collections4</artifactId>
            <version>4.4</version>
        </dependency>
        <dependency>
            <groupId>commons-codec</groupId>
            <artifactId>commons-codec</artifactId>
            <version>1.16.0</version>
        </dependency>

        <!-- 测试 -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit5</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.rest-assured</groupId>
            <artifactId>rest-assured</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.quarkus.platform</groupId>
                <artifactId>quarkus-maven-plugin</artifactId>
                <version>${quarkus.platform.version}</version>
                <extensions>true</extensions>
                <executions>
                    <execution>
                        <goals>
                            <goal>build</goal>
                            <goal>generate-code</goal>
                            <goal>generate-code-tests</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            
            <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>${compiler-plugin.version}</version>
                <configuration>
                    <compilerArgs>
                        <arg>-parameters</arg>
                    </compilerArgs>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>1.18.30</version>
                        </path>
                        <path>
                            <groupId>org.mapstruct</groupId>
                            <artifactId>mapstruct-processor</artifactId>
                            <version>${mapstruct.version}</version>
                        </path>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok-mapstruct-binding</artifactId>
                            <version>${lombok-mapstruct-binding.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <profiles>
        <profile>
            <id>native</id>
            <activation>
                <property>
                    <name>native</name>
                </property>
            </activation>
            <properties>
                <skipITs>false</skipITs>
                <quarkus.package.type>native</quarkus.package.type>
            </properties>
        </profile>
    </profiles>
</project>
```

### 1.4 配置文件迁移

#### application.properties → application.properties

```properties
# 应用配置
quarkus.application.name=AirOpsCat
quarkus.http.port=8080

# 数据源配置
quarkus.datasource.db-kind=sqlite
quarkus.datasource.jdbc.url=jdbc:sqlite:admin.db
quarkus.datasource.jdbc.driver=org.sqlite.JDBC

# Hibernate ORM 配置
quarkus.hibernate-orm.database.generation=update
quarkus.hibernate-orm.log.sql=false
quarkus.hibernate-orm.dialect=org.hibernate.community.dialect.SQLiteDialect

# 日志配置
quarkus.log.level=INFO
quarkus.log.category."com.fun90.airopscat".level=DEBUG
quarkus.log.file.enable=true
quarkus.log.file.path=./logs/airopscat.log
quarkus.log.file.rotation.max-file-size=10M
quarkus.log.file.rotation.max-backup-index=30

# 会话配置 (如果使用传统 servlet)
quarkus.servlet.context-path=/
quarkus.http.session.timeout=30m

# 静态资源配置
quarkus.http.static-resources."/"=META-INF/resources

# 开发模式配置
quarkus.live-reload.instrumentation=true

# 自定义配置
airopscat.ssh.provider=jsch
airopscat.subscription.url=http://localhost:8080/subscribe
airopscat.crypto.secret-key=AirOpsCatDefaultSecretKey2024
airopscat.bark.url=https://example.com
airopscat.bark.device-key=DJfKO3K0ZvEorcBm
airopscat.online.check-minutes=5
airopscat.apple.id=your_apple_id_here
airopscat.apple.pwd=your_apple_pwd_here
airopscat.api.token=your_api_token_here
airopscat.docs.url=https://docs.xxx.com
```

## 阶段二：核心模块迁移

### 2.1 主应用类迁移

#### Spring Boot → Quarkus

**原 Spring Boot 主类**:
```java
@SpringBootApplication
public class AirOpsCatApplication {
    public static void main(String[] args) {
        SpringApplication.run(AirOpsCatApplication.class, args);
    }
}
```

**新 Quarkus 主类**:
```java
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class AirOpsCatApplication implements QuarkusApplication {
    @Override
    public int run(String... args) throws Exception {
        Quarkus.waitForExit();
        return 0;
    }
}
```

### 2.2 实体类迁移

实体类基本保持不变，只需进行包名迁移：

```java
// 将所有 javax.persistence.* 包改为 jakarta.persistence.*
import jakarta.persistence.*;
```

### 2.3 Repository 层迁移策略

#### 策略A：使用 Spring Data JPA 兼容层 (渐进式)
保持现有 Repository 接口不变，利用 `quarkus-spring-data-jpa` 扩展：

```java
// 保持不变
public interface AccountRepository extends JpaRepository<Account, Long> {
    // 方法保持不变
}
```

#### 策略B：迁移到 Hibernate ORM with Panache (推荐)
```java
// 新的 Panache Repository
@ApplicationScoped
public class AccountRepository implements PanacheRepository<Account> {
    
    public List<Account> findByUsername(String username) {
        return find("username", username).list();
    }
    
    public Optional<Account> findByUsernameOptional(String username) {
        return find("username", username).firstResultOptional();
    }
}
```

### 2.4 Service 层迁移

```java
// 原 Spring Service
@Service
@Transactional
public class AccountService {
    @Autowired
    private AccountRepository accountRepository;
}

// 迁移到 Quarkus (使用兼容层)
@Component  // Spring 兼容
@Transactional
public class AccountService {
    @Autowired
    private AccountRepository accountRepository;
}

// 或使用 CDI (推荐)
@ApplicationScoped
@Transactional
public class AccountService {
    @Inject
    AccountRepository accountRepository;
}
```

### 2.5 Controller 层迁移

#### 策略A：使用 Spring Web 兼容层
```java
// 基本保持不变
@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    // 方法保持不变
}
```

#### 策略B：迁移到 JAX-RS (推荐)
```java
@Path("/api/accounts")
@ApplicationScoped
public class AccountController {
    
    @Inject
    AccountService accountService;
    
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<AccountDto> getAllAccounts() {
        return accountService.findAll();
    }
    
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createAccount(AccountRequest request) {
        AccountDto account = accountService.create(request);
        return Response.status(Response.Status.CREATED).entity(account).build();
    }
}
```

## 阶段三：安全与模板迁移

### 3.1 Spring Security 迁移

#### 使用兼容层 (过渡期)
```java
// 保持现有配置基本不变
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    // 配置保持不变
}
```

#### 迁移到 Quarkus Security (推荐)
```java
@ApplicationScoped
public class SecurityIdentityAugmentor implements SecurityIdentityAugmentor {
    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        // 实现身份增强逻辑
        return Uni.createFrom().item(identity);
    }
}
```

### 3.2 Thymeleaf → Qute 模板迁移

#### Thymeleaf 模板示例
```html
<!-- Spring + Thymeleaf -->
<div th:if="${user != null}">
    <h1 th:text="${user.name}">User Name</h1>
    <ul>
        <li th:each="account : ${user.accounts}" 
            th:text="${account.username}">Account</li>
    </ul>
</div>
```

#### Qute 模板迁移
```html
<!-- Quarkus + Qute -->
{#if user}
    <h1>{user.name}</h1>
    <ul>
        {#for account in user.accounts}
            <li>{account.username}</li>
        {/for}
    </ul>
{/if}
```

#### 模板 Controller 迁移
```java
// Spring MVC
@Controller
public class HomeController {
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("users", userService.findAll());
        return "home";
    }
}

// Quarkus + Qute
@Path("/")
public class HomeController {
    
    @Inject
    Template home; // 注入 home.html 模板
    
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance home() {
        return home.data("users", userService.findAll());
    }
}
```

## 阶段四：测试与优化

### 4.1 测试迁移

#### Spring Boot Test → Quarkus Test
```java
// Spring Boot
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountServiceTest {
    @Autowired
    private AccountService accountService;
}

// Quarkus
@QuarkusTest
class AccountServiceTest {
    @Inject
    AccountService accountService;
    
    @Test
    void testCreateAccount() {
        // 测试逻辑
    }
}
```

### 4.2 构建命令迁移

```bash
# Spring Boot 构建
./mvnw clean package
java -jar target/airopscat-1.0.2.jar

# Spring Boot Native
./mvnw -Pnative native:compile

# Quarkus 构建
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar

# Quarkus 开发模式
./mvnw quarkus:dev

# Quarkus Native
./mvnw package -Dnative
```

### 4.3 性能优化配置

```properties
# 生产环境优化
quarkus.package.type=uber-jar
quarkus.hibernate-orm.compile-time-store=true
quarkus.http.io-threads=8
quarkus.vertx.worker-pool-size=20

# Native 镜像优化
quarkus.native.resources.includes=**/*.properties,**/*.html,**/*.js,**/*.css
quarkus.native.additional-build-args=--allow-incomplete-classpath,--report-unsupported-elements-at-runtime
```

## 迁移时间表

| 阶段 | 预估时间 | 关键里程碑 |
|---|---|---|
| 阶段一 | 1-2 周 | 项目搭建完成，基础配置迁移 |
| 阶段二 | 2-3 周 | 核心业务逻辑迁移完成 |
| 阶段三 | 2-3 周 | 安全和模板系统迁移完成 |
| 阶段四 | 1-2 周 | 测试通过，性能优化完成 |
| **总计** | **6-10 周** | 完整迁移到 Quarkus |

## 迁移验证清单

### 功能验证
- [ ] 用户认证和授权正常
- [ ] 数据库操作正常
- [ ] REST API 接口正常
- [ ] 模板渲染正常
- [ ] SSH 连接功能正常
- [ ] 推送通知功能正常

### 性能验证
- [ ] 启动时间对比
- [ ] 内存使用对比
- [ ] 响应时间对比
- [ ] Native 镜像构建成功

### 部署验证
- [ ] JAR 包部署正常
- [ ] Native 镜像部署正常
- [ ] 容器化部署正常

## 风险评估与缓解

### 主要风险
1. **Spring 兼容层限制**: 某些 Spring 功能可能不完全兼容
2. **模板迁移复杂度**: Thymeleaf 到 Qute 需要重写模板
3. **性能回归**: 迁移过程中可能出现性能问题
4. **第三方库兼容性**: 如 JSch 等库在 Quarkus 中的兼容性

### 缓解策略
1. **分阶段迁移**: 降低单次变更风险
2. **充分测试**: 每个阶段都进行全面测试
3. **性能监控**: 持续监控关键性能指标
4. **回滚准备**: 保持 Spring Boot 版本作为备选方案

## 总结

本迁移计划采用渐进式方法，充分利用 Quarkus 的 Spring 兼容层来降低迁移风险。通过分阶段实施，可以确保系统的稳定性和可靠性，同时享受 Quarkus 带来的性能提升和云原生特性。