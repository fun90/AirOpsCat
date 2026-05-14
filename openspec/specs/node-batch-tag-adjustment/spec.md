## Purpose

定义节点批量标签调整的应用模式、兼容性默认行为、有效标签变更计算，以及标签变更后部署状态重置规则，确保管理员可以安全地区分替换和追加操作，并且只有真实发生标签变化的节点进入待重新部署状态。

## Requirements

### Requirement: 管理员 SHALL 可选择批量节点标签更新的应用方式
系统 SHALL 为批量节点标签调整提供模式选择器，使管理员能够明确选择用已选标签替换当前标签集合，或将已选标签追加到当前标签集合。

#### Scenario: Replace mode is available in the batch tag modal
- **WHEN** 管理员打开节点批量标签调整弹窗
- **THEN** 系统 SHALL 显示替换选项，并说明会用勾选标签覆盖每个已选节点的当前标签

#### Scenario: Append mode is available in the batch tag modal
- **WHEN** 管理员打开节点批量标签调整弹窗
- **THEN** 系统 SHALL 显示追加选项，并说明会保留当前标签并加入勾选标签

### Requirement: 批量节点标签更新 SHALL 为兼容性默认使用替换语义
批量节点标签更新 API SHALL 将未显式提供模式的请求视为替换操作，使既有调用方保持当前行为。

#### Scenario: Legacy request omits the mode field
- **WHEN** 批量节点标签更新端点收到包含 `nodeIds` 和 `tagIds` 但不包含模式值的请求
- **THEN** 系统 SHALL 用提交的标签替换每个已选节点的标签集合

### Requirement: 替换和追加模式 SHALL 正确计算有效标签变更
系统 SHALL 根据所选模式为每个已选节点计算目标标签集合，并且仅当有效标签集合不同于当前标签集合时才将节点标记为已变更。

#### Scenario: Replace mode overwrites the current tag set
- **WHEN** 管理员以替换模式提交批量标签调整
- **THEN** 每个已选节点的目标标签集合 SHALL 正好等于勾选标签

#### Scenario: Append mode preserves current tags
- **WHEN** 管理员以追加模式提交批量标签调整
- **THEN** 每个已选节点 SHALL 保留当前标签，并获得尚不存在的勾选标签

#### Scenario: Unchanged nodes remain unchanged in append mode
- **WHEN** 某个已选节点已经包含追加模式下勾选的全部标签
- **THEN** 系统 SHALL 保持该节点的标签集合和部署状态不变，并在批量结果中将其计为未变更

### Requirement: 有效批量标签变更 SHALL 重置部署状态
系统 SHALL 仅将批量标签调整期间有效标签集合发生变化的节点标记为未部署，以便后续部署流程重新应用依赖标签的配置。

#### Scenario: Changed node becomes undeployed
- **WHEN** 节点的有效标签集合通过替换或追加模式发生变化
- **THEN** 系统 SHALL 在返回成功前将该节点的部署标记设置为未部署

#### Scenario: Unchanged node keeps its deployment state
- **WHEN** 节点在批量请求前后的有效标签集合完全一致
- **THEN** 系统 SHALL 不改变该节点的部署标记
