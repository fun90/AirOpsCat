## 1. 运行目标查询

- [x] 1.1 在服务器或节点仓库中新增查询，返回未禁用、未过期、非外部托管且至少存在一个已部署启用节点的服务器
- [x] 1.2 为目标服务器查询补充无节点、待部署、已部署、待删除、节点禁用、服务器禁用、服务器过期和外部托管场景测试

## 2. 流量采集移除 ServerConfig 依赖

- [x] 2.1 修改 `TrafficStatsTask`，直接按目标服务器执行每台服务器一次的 sing-box 流量采集
- [x] 2.2 删除流量采集中的 `configType`、`enabled` 和配置分组判断
- [x] 2.3 删除 `SingBoxTrafficStatsCollector.collectUserTrafficStats()` 的 `ServerConfig` 参数
- [x] 2.4 更新 `TrafficStatsTaskTest`，验证同一服务器只采集一次并正确跳过不符合条件的服务器

## 3. 限速同步移除 ServerConfig 依赖

- [x] 3.1 修改 `RateLimitService`，使用目标服务器查询替代 `ServerConfigRepository.findEnabledServerIdsByConfigTypes()`
- [x] 3.2 统一单服务器同步与全量同步的服务器有效性和已部署节点判断
- [x] 3.3 增加限速同步目标筛选测试，覆盖无已部署节点和服务器不可运行场景

## 4. 备份清理移除 ServerConfig 依赖

- [x] 4.1 修改 `CoreConfigCleanupTask`，直接遍历目标服务器并固定使用 `/etc/sing-box/config.json`
- [x] 4.2 删除备份清理中的内核类型和数据库配置路径判断
- [x] 4.3 增加备份目录解析、目标服务器筛选和旧备份删除测试

## 5. 部署流程停止保存完整配置

- [x] 5.1 从 `CoreDeploymentExecutor` 删除 `ServerConfigRepository` 依赖及 `saveServerConfig()`、状态协调和新建配置逻辑
- [x] 5.2 确认远端部署成功后仅更新节点状态并记录节点部署版本
- [x] 5.3 增加回归测试，验证超过 64KB 的生成配置不会触发数据库配置正文写入且部署结果可以正常返回
- [x] 5.4 验证配置预览仍通过 `NodeDeploymentService.previewServerConfigs()` 动态构建

## 6. 删除配置管理模块

- [x] 6.1 删除 `ServerConfigController`、`ServerConfigService`、`ServerConfigRepository` 和 `ServerConfig` 实体
- [x] 6.2 删除 `ServerConfigDto`、`ServerConfigRequest`、`ServerConfigConverter` 及 JSON 反射注册
- [x] 6.3 删除 `/vpn/server-config` 页面注册、模板和 `server-config.js`
- [x] 6.4 全局检索并清除生产代码和测试中的全部 `ServerConfig`、`server_config` 与 `/api/admin/server-configs` 引用

## 7. 数据库与启动迁移清理

- [x] 7.1 删除 `ServerConfigColumnMigration` 和 `V2026061401__expand_server_config_column.sql`
- [x] 7.2 新增数据库迁移 `DROP TABLE IF EXISTS server_config`
- [x] 7.3 检查 SQLite 到 MySQL 等数据迁移脚本，删除 `server_config` 表定义、导出顺序和字段映射
- [x] 7.4 验证空数据库初始化和现有数据库升级均可正常启动

## 8. 集成验证

- [x] 8.1 运行完整单元测试并修复所有受影响测试
- [x] 8.2 验证单节点部署、批量节点部署和账户关联节点部署成功
- [x] 8.3 验证删除最后一个节点后远端配置刷新成功，随后停止该服务器的流量采集和限速同步
- [x] 8.4 验证流量采集、限速全量同步和配置备份清理在没有 `server_config` 表时正常运行
- [x] 8.5 验证服务器配置动态预览可用，配置管理页面及旧 API 已移除
- [x] 8.6 执行 `git diff --check` 并确认未修改或删除无关工作区文件
