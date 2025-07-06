# AirOpsCat 订阅功能使用说明

## 功能概述

AirOpsCat 支持根据账户的 authCode 生成各种客户端应用的订阅配置文件，使用 Mustache 模板引擎来渲染不同平台和应用的配置。

## 订阅链接格式

```
/subscribe/{authCode}/{osName}/{appName}
```

### 参数说明

- `authCode`: 账户的认证码（Account.authCode）
- `osName`: 操作系统名称
- `appName`: 应用程序名称

### 支持的平台和应用

| 操作系统 | 应用名称 | 示例链接 |
|---------|---------|---------|
| linux | clash-verge | `/subscribe/abc123def456/linux/clash-verge` |
| windows | clash-verge | `/subscribe/abc123def456/windows/clash-verge` |
| macos | clash-verge | `/subscribe/abc123def456/macos/clash-verge` |
| ios | shadowrocket | `/subscribe/abc123def456/ios/shadowrocket` |
| ios | loon | `/subscribe/abc123def456/ios/loon` |
| ios | stash | `/subscribe/abc123def456/ios/stash` |
| android | clash-meta | `/subscribe/abc123def456/android/clash-meta` |
| harmony | clash-meta | `/subscribe/abc123def456/harmony/clash-meta` |

## 功能特性

### 1. 账户验证
- 根据 authCode 查找对应的账户
- 验证账户是否激活（未过期且未禁用）
- 账户不存在或已失效时返回 404

### 2. 节点过滤
- 获取账户标签关联的可用节点
- 过滤已部署且启用的节点
- 自动排除未部署或已禁用的节点

### 3. 协议支持
支持多种代理协议：
- **VLESS**: 使用账户 UUID 作为用户ID
- **Shadowsocks**: 使用 authCode 作为密码
- **SOCKS**: 使用 UUID 作为用户名，authCode 作为密码
- **Hysteria2**: 使用 authCode 作为密码

### 4. 模板系统
- 每个平台和应用都有独立的 Mustache 模板
- 模板位置：`src/main/resources/templates/subscription/{osName}/{appName}.mustache`
- 支持模板热加载和缓存机制
- 如果找不到特定模板，会使用默认模板

### 5. 配置生成
生成的配置包含：
- 代理服务器列表
- 代理组配置（自动选择、手动选择、漏网之鱼等）
- DNS 配置
- 路由规则（中国大陆直连、私有网络直连等）

## 使用示例

### 1. 获取 Clash 配置

```bash
curl "https://your-domain.com/subscribe/your-auth-code/linux/clash-verge"
```

### 2. 获取 Shadowrocket 配置

```bash
curl "https://your-domain.com/subscribe/your-auth-code/ios/shadowrocket"
```

### 3. 在客户端中使用

大多数客户端支持通过订阅链接自动导入配置：

1. 打开你的代理客户端
2. 找到"添加订阅"或"Import"选项
3. 输入订阅链接
4. 客户端会自动下载并解析配置

## 响应格式

### 成功响应
- HTTP 状态码：200
- Content-Type: text/plain
- 文件名：{appName}.yaml（通过 Content-Disposition 头设置）
- 内容：根据模板生成的配置文件

### 错误响应
- HTTP 状态码：404 - 账户不存在或无可用节点
- HTTP 状态码：400 - 请求参数错误

## 模板变量

在 Mustache 模板中可以使用以下变量：

```mustache
{{timestamp}}         # 生成时间戳
{{account.uuid}}      # 账户 UUID
{{account.authCode}}  # 账户认证码
{{osName}}           # 操作系统名称
{{appName}}          # 应用名称

# 代理列表
{{#proxies}}
  {{name}}           # 代理名称
  {{type}}           # 代理类型
  {{server}}         # 服务器地址
  {{port}}           # 端口
  {{uuid}}           # UUID（如果有）
  {{password}}       # 密码（如果有）
  {{cipher}}         # 加密方式（如果有）
  {{username}}       # 用户名（如果有）
{{/proxies}}

# 代理名称列表
{{#proxyNames}}
  {{.}}              # 代理名称
{{/proxyNames}}
```

## 安全考虑

1. **认证码保护**: authCode 是敏感信息，应妥善保管
2. **HTTPS**: 生产环境建议使用 HTTPS 保护传输安全
3. **访问控制**: 可以考虑添加访问频率限制
4. **日志记录**: 记录订阅访问日志以便审计

## 自定义模板

如需自定义模板：

1. 在 `src/main/resources/templates/subscription/` 目录下创建对应的文件夹结构
2. 创建 `.mustache` 模板文件
3. 使用上述模板变量编写配置
4. 重启应用使模板生效

## 故障排除

### 1. 订阅链接无法访问
- 检查账户是否存在且认证码正确
- 确认账户未过期且未被禁用
- 验证账户是否有关联的可用节点

### 2. 生成的配置无法使用
- 检查节点是否已部署且启用
- 确认服务器连接信息正确
- 验证协议配置是否匹配

### 3. 模板渲染错误
- 检查模板文件语法是否正确
- 确认模板变量是否存在
- 查看应用日志获取详细错误信息 