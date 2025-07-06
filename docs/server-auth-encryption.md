# 服务器认证信息加密功能

## 概述

为了提高安全性，本系统对服务器的认证信息（密码和私钥）进行了加密存储。所有敏感的认证信息在保存到数据库前会自动加密，在使用时会自动解密。

## 功能特性

### 1. 透明加密解密
- **保存时自动加密**：当保存服务器信息到数据库时，JPA转换器自动加密`auth`字段
- **查询时自动解密**：从数据库查询服务器信息时，JPA转换器自动解密`auth`字段
- **业务代码透明**：业务代码始终操作明文数据，加密解密过程完全透明
- **向后兼容**：现有的明文认证信息可以正常使用，系统会自动检测并兼容

### 2. 安全特性
- 使用 **AES-256** 加密算法
- 支持自定义加密密钥
- 密钥可通过配置文件或环境变量设置
- 错误处理：解密失败时会回退到原始数据

### 3. 透明操作
- 对用户界面和业务代码完全透明
- Server实体对象中的auth字段始终是明文
- API接口返回的数据为明文
- 数据库中存储的始终是加密数据

## 配置说明

### 密钥配置

在 `application.properties` 中配置加密密钥：

```properties
# 加密配置
airopscat.crypto.secret-key=YourSecretKey2024
```

**生产环境建议：**
1. 使用环境变量设置密钥：
   ```bash
   export AIROPSCAT_CRYPTO_SECRET_KEY=YourSecureRandomKey
   ```

2. 生成安全的密钥：
   ```java
   String secureKey = CryptoUtil.generateSecretKey();
   ```

## 使用方法

### 1. 服务器管理

#### 添加服务器
```java
Server server = new Server();
server.setIp("192.168.1.100");
server.setUsername("root");
server.setAuthType("PASSWORD");
server.setAuth("myPassword123");  // 明文密码
serverService.saveServer(server);  // 自动加密保存
```

#### 获取服务器信息
```java
// 直接从实体获取明文认证信息（JPA转换器自动解密）
Server server = serverService.getServerById(serverId);
String auth = server.getAuth();  // 直接获得明文认证信息

// 获取DTO（也是明文数据）
ServerDto dto = serverService.convertToDto(server);
String password = dto.getAuth();  // 明文认证信息

// 直接获取认证信息
String auth2 = serverService.getAuth(serverId);
```

### 2. SSH连接

系统在建立SSH连接时直接使用Server实体中的明文认证信息：

```java
// NodeDeploymentService 中的使用示例
private SshConfig createSshConfig(Server server) {
    SshConfig sshConfig = new SshConfig();
    sshConfig.setHost(server.getIp());
    // 直接使用明文认证信息，JPA转换器已自动解密
    String auth = server.getAuth();
    
    if ("PASSWORD".equalsIgnoreCase(server.getAuthType())) {
        sshConfig.setPassword(auth);
    } else {
        sshConfig.setPrivateKeyContent(auth);
    }
    return sshConfig;
}
```

### 3. 验证认证信息

```java
// 验证认证信息（现在直接比较明文）
boolean matches = serverService.verifyAuth(serverId, "inputPassword");

// 获取服务器认证信息
String auth = serverService.getAuth(serverId);
```

## API 接口

所有现有的API接口无需修改，加密解密过程完全透明：

### 添加服务器
```http
POST /api/admin/servers
Content-Type: application/json

{
    "ip": "192.168.1.100",
    "username": "root",
    "authType": "PASSWORD",
    "auth": "myPassword123"
}
```

### 获取服务器信息
```http
GET /api/admin/servers/1

Response:
{
    "id": 1,
    "ip": "192.168.1.100",
    "username": "root",
    "authType": "PASSWORD",
    "auth": "myPassword123"  // 自动解密后的明文
}
```

## 数据迁移

### 现有数据处理

现有的明文认证信息在下次更新时会自动加密：

1. **自动迁移**：系统会检测现有的明文数据，在下次保存时自动加密
2. **手动迁移**：可以通过批量更新来加密现有数据

### 批量加密脚本示例

```java
@Service
public class DataMigrationService {
    
    @Autowired
    private ServerService serverService;
    
    @Autowired
    private ServerRepository serverRepository;
    
    public void encryptExistingPasswords() {
        List<Server> servers = serverRepository.findAll();
        
        for (Server server : servers) {
            if (StringUtils.hasText(server.getAuth()) && 
                !serverService.isAuthEncrypted(server.getId())) {
                // 重新保存，触发加密
                serverService.updateServer(server);
            }
        }
    }
}
```

## 安全建议

### 1. 密钥管理
- 使用强随机密钥（建议32字符以上）
- 定期更换密钥
- 不要在代码中硬编码密钥
- 使用环境变量或安全的密钥管理服务

### 2. 环境配置
- 生产环境必须使用自定义密钥
- 开发和生产环境使用不同的密钥
- 密钥文件的访问权限要严格控制

### 3. 备份恢复
- 备份时要同时备份密钥
- 恢复时确保密钥一致
- 考虑密钥轮换时的数据迁移策略

## 故障排除

### 1. 解密失败
如果遇到解密失败的情况：
- 检查密钥配置是否正确
- 确认是否使用了正确的密钥
- 系统会自动回退到原始数据

### 2. 兼容性问题
- 系统自动检测数据是否已加密
- 明文数据可以正常使用
- 混合数据环境下系统会自动处理

### 3. 性能考虑
- 加密解密操作对性能影响很小
- 在高频访问场景下可以考虑缓存解密后的数据
- 大批量操作时可以使用批处理优化

## 测试

系统提供了完整的测试用例来验证加密功能：

```bash
# 运行加密功能测试
mvn test -Dtest=CryptoUtilTest
```

测试覆盖了以下场景：
- 基本加密解密
- 空值处理
- 特殊字符支持
- 长文本处理
- 密钥生成
- 数据验证 