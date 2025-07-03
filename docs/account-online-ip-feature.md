# 账户在线IP统计功能

## 概述

AirOpsCat 系统新增了账户在线IP统计功能，用于记录和跟踪用户账户的在线IP地址信息。该功能可以帮助管理员监控用户的在线状态和IP使用情况。

## 功能特性

### 1. 实时在线状态更新
- 节点服务器每3分钟调用接口同步在线状态
- 自动识别重复连接并更新最后在线时间
- 支持多节点、多IP的在线记录

### 2. 智能记录管理
- 根据配置的时间范围（默认5分钟）检查重复记录
- 自动清理过期的在线记录
- 支持按邮箱、节点IP等维度查询

### 3. 定时清理任务
- 每小时自动清理过期的在线记录
- 清理时间范围为配置时间的2倍（默认10分钟）

## 配置说明

### 检查时间配置
在 `application.properties` 中配置检查时间范围：

```properties
# 在线IP统计配置
# 检查时间范围（分钟），默认为5分钟
airopscat.online.check-minutes=5
```

### 定时任务配置
系统会自动创建数据库表并启动定时清理任务，无需额外配置。

## API 接口

### 1. 更新在线状态
**接口地址**: `POST /api/admin/accounts/online/{nodeIp}`

**请求头**:
```
Token: {apiToken}
```

**请求体**:
```json
{
    "email": "user@example.com",
    "clientIp": "192.168.1.100"
}
```

**响应**: `200 OK`

### 2. 查询在线记录

#### 按邮箱查询
**接口地址**: `GET /api/admin/accounts/online/email/{email}`

**响应示例**:
```json
[
    {
        "id": 1,
        "email": "user@example.com",
        "clientIp": "192.168.1.100",
        "nodeIp": "10.0.0.1",
        "lastOnlineTime": "2024-01-01T12:00:00",
        "createTime": "2024-01-01T11:55:00",
        "updateTime": "2024-01-01T12:00:00",
        "accountNo": "ACC001",
        "userNickName": "张三",
        "accountId": 1,
        "userId": 1
    }
]
```

#### 按节点IP查询
**接口地址**: `GET /api/admin/accounts/online/node/{nodeIp}`

#### 查询所有在线记录
**接口地址**: `GET /api/admin/accounts/online/all`

### 3. 清理过期记录
**接口地址**: `DELETE /api/admin/accounts/online/cleanup`

**响应**: `200 OK`

## 数据库表结构

### account_online_ip 表
```sql
CREATE TABLE account_online_ip (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    client_ip VARCHAR(255) NOT NULL,
    node_ip VARCHAR(255) NOT NULL,
    last_online_time DATETIME NOT NULL,
    create_time DATETIME,
    update_time DATETIME
);
```

## 使用示例

### 1. 节点服务器集成
在节点服务器上设置定时任务，每3分钟调用一次在线状态更新接口：

```bash
#!/bin/bash
# 每3分钟执行一次
*/3 * * * * curl -X POST \
  -H "Content-Type: application/json" \
  -H "Token: your-api-token" \
  -d '{"email":"user@example.com","clientIp":"192.168.1.100"}' \
  http://your-server:8080/api/admin/accounts/online/10.0.0.1
```

### 2. 前端集成
在前端页面中展示在线用户信息：

```javascript
// 获取所有在线记录
fetch('/api/admin/accounts/online/all')
    .then(response => response.json())
    .then(data => {
        console.log('在线用户:', data);
        // 更新UI显示
    });
```

## 注意事项

1. **API Token 验证**: 生产环境中需要实现API Token的验证逻辑
2. **网络延迟**: 考虑网络延迟对在线状态判断的影响
3. **数据清理**: 系统会自动清理过期数据，但建议定期检查数据量
4. **性能考虑**: 大量并发请求时注意数据库性能优化

## 故障排除

### 1. 在线记录不更新
- 检查节点服务器的定时任务是否正常运行
- 验证API接口的网络连接
- 检查Token是否正确

### 2. 数据清理异常
- 查看应用日志中的定时任务执行情况
- 检查数据库连接状态
- 验证配置参数是否正确

### 3. 查询接口返回空数据
- 确认查询条件是否正确
- 检查数据库中是否有对应的记录
- 验证时间范围设置是否合理

## 扩展功能

### 1. 在线用户统计
可以基于在线记录实现：
- 实时在线用户数量统计
- 用户在线时长分析
- IP地址使用频率统计

### 2. 异常检测
可以基于在线记录实现：
- 异常IP地址检测
- 多设备同时在线检测
- 地理位置异常检测

### 3. 报表功能
可以基于在线记录生成：
- 用户在线时间报表
- IP使用情况报表
- 节点负载分布报表 