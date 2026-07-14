## Why

节点部署生成的完整 sing-box 配置已经写入远端服务器，数据库中的 `ServerConfig` 只是重复保存同一份大型配置正文。该重复写入不仅增加存储和敏感数据暴露，还会在远端部署成功后因数据库字段容量或持久化异常导致接口返回失败，形成远端状态与管理端结果不一致。

## What Changes

- **BREAKING** 删除 `ServerConfig` 实体、仓库、服务、DTO、转换器及 `server_config` 数据表。
- **BREAKING** 删除服务器配置管理控制台页面，以及 `/api/admin/server-configs` 下的查询、创建、修改、删除、统计和手工上传接口。
- 节点部署成功后不再持久化完整 sing-box 配置正文，仅更新节点部署状态和节点部署版本。
- 流量采集不再通过 `server_config` 判断目标服务器，改为从有效服务器及其已部署、启用节点推导。
- 限速全量同步不再通过 `server_config` 判断 sing-box 已启用服务器，改为从有效服务器及其已部署、启用节点推导。
- 内核配置备份清理不再读取数据库配置路径，统一使用固定的 sing-box 配置路径 `/etc/sing-box/config.json`。
- 保留服务器配置动态预览能力；预览继续根据当前节点、账户、路由和系统配置即时构建，不读取历史配置正文。
- 删除 `server_config.config` 扩容为 `LONGTEXT` 的启动迁移和相关迁移 SQL。
- 提供数据库迁移删除 `server_config` 表，并确保升级过程中先完成运行时依赖替换。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `server-and-node-deployment`：节点部署不再保存或管理服务器级配置正文，部署、流量采集、限速同步和备份清理改为基于服务器与节点的真实状态运行。

## Impact

- 后端：`CoreDeploymentExecutor`、`TrafficStatsTask`、`SingBoxTrafficStatsCollector`、`RateLimitService`、`CoreConfigCleanupTask`、控制台页面注册、JSON 反射注册及数据库初始化/迁移逻辑。
- 删除模块：`ServerConfigController`、`ServerConfigService`、`ServerConfigRepository`、`ServerConfig`、`ServerConfigDto`、`ServerConfigRequest`、`ServerConfigConverter`。
- 前端：删除 `/vpn/server-config` 页面、模板和 JavaScript。
- API：删除 `/api/admin/server-configs/**`，属于管理端破坏性变更。
- 数据库：删除 `server_config` 表；不迁移其中的配置正文。
- 运维：远端 `/etc/sing-box/config.json` 及其备份文件继续保留为实际运行配置和短期恢复来源。
