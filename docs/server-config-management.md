# 服务器配置管理模块

## 功能概述

配置管理模块提供了对服务器配置的完整管理功能，包括分页查询、创建、更新、删除和上传配置到服务器。

## 主要功能

### 1. 分页查询
- 支持按服务器IP、主机名、配置类型进行关键字搜索
- 支持按配置类型筛选
- 分页显示，默认每页10条记录
- 按创建时间倒序排列

### 2. 配置管理
- **创建配置**: 为指定服务器创建新的配置
- **编辑配置**: 修改现有配置的内容
- **删除配置**: 删除不需要的配置
- **查看配置**: 以只读模式查看配置内容

### 3. 配置上传
- 通过SSH连接将配置内容上传到目标服务器
- 自动重启相关服务以应用新配置
- 支持多种配置类型（xray、hysteria、hysteria2等）

## 技术实现

### 后端架构

#### 实体类
- `ServerConfig`: 服务器配置实体
- `Server`: 服务器实体（关联关系）

#### DTO类
- `ServerConfigDto`: 数据传输对象
- `ServerConfigRequest`: 请求数据传输对象

#### 服务层
- `ServerConfigService`: 核心业务逻辑服务
- `CoreManagementService`: 内核管理服务（用于SSH操作）

#### 控制器
- `ServerConfigController`: REST API控制器

#### 数据访问层
- `ServerConfigRepository`: 数据访问接口

### 前端架构

#### JavaScript
- `config.js`: 配置管理的前端逻辑
- 基于Vue.js和DataTable组件

#### HTML模板
- `config.html`: 配置管理的页面模板
- 包含统计卡片、过滤器、数据表格和模态框

## API接口

### 查询接口
- `GET /api/admin/server-configs` - 分页查询配置
- `GET /api/admin/server-configs/{id}` - 根据ID获取配置
- `GET /api/admin/server-configs/server/{serverId}` - 根据服务器ID获取配置
- `GET /api/admin/server-configs/types` - 获取配置类型选项
- `GET /api/admin/server-configs/stats` - 获取统计信息
- `GET /api/admin/server-configs/servers` - 获取服务器选项

### 管理接口
- `POST /api/admin/server-configs` - 创建配置
- `PUT /api/admin/server-configs/{id}` - 更新配置
- `DELETE /api/admin/server-configs/{id}` - 删除配置

### 上传接口
- `POST /api/admin/server-configs/{id}/upload` - 上传配置到服务器

## 配置类型

支持以下配置类型：
- `xray`: Xray核心配置
- `hysteria`: Hysteria配置
- `hysteria2`: Hysteria2配置

## 使用示例

### 创建配置
```json
POST /api/admin/server-configs
{
    "serverId": 1,
    "configType": "xray",
    "config": "{\"log\":{\"loglevel\":\"warning\"},\"inbounds\":[]}",
    "path": "/etc/xray/config.json"
}
```

### 上传配置
```bash
POST /api/admin/server-configs/1/upload
```

## 安全考虑

1. **SSH连接安全**: 使用加密的SSH连接进行配置上传
2. **权限控制**: 通过Spring Security进行访问控制
3. **输入验证**: 对配置内容进行格式验证
4. **错误处理**: 完善的异常处理和错误信息返回

## 扩展性

模块设计具有良好的扩展性：
- 支持添加新的配置类型
- 支持自定义配置验证规则
- 支持不同的SSH认证方式
- 支持批量操作

## 依赖关系

- Spring Boot 3.x
- Spring Data JPA
- Spring Security
- Apache SSHD (SSH连接)
- Vue.js (前端)
- Tabler UI (前端样式) 