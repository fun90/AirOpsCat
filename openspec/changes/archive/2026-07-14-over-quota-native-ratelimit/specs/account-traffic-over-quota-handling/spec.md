## MODIFIED Requirements

### Requirement: 超额状态变化后必须请求配置刷新

系统 SHALL 在账户流量统计任务检测到账户流量超额或超额状态恢复后，请求限速配置刷新流程，使远端 sing-box 配置尽快包含重新计算后的有效限速（含超配覆盖）。

#### Scenario: 账户进入流量超额状态
- **WHEN** 系统检测到账户从未超额变为流量超额
- **THEN** 系统 SHALL 触发限速同步（`RateLimitService.triggerAsyncSync()`）
- **AND** 该同步 SHALL 同时更新 `accounts.json`（Agent 路径）和 sing-box 配置（原生限速路径）

#### Scenario: 账户从流量超额恢复
- **WHEN** 系统检测到账户从流量超额恢复为未超额
- **THEN** 系统 SHALL 触发限速同步（`RateLimitService.triggerAsyncSync()`）
- **AND** 该同步 SHALL 同时更新 `accounts.json` 和 sing-box 配置，恢复账号原始限速值

#### Scenario: 全局限速开关关闭
- **WHEN** 账户流量超额且全局限速开关关闭
- **THEN** 系统 SHALL 仍记录告警状态并发送告警
- **AND** 远端实际限速 SHALL 由现有限速同步开关控制，sing-box 配置重推同样受该开关约束
