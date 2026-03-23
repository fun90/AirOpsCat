# 通用筛选组件说明

## 目标

这套筛选组件不是为节点页单独定制，而是抽成可复用的公共能力，方便后续应用到其他列表页。

设计原则：

- 保持页面原有搜索框和筛选区样式
- 桌面端和移动端共用同一份业务筛选字段定义
- 公共层只负责通用外壳
- 业务页面只负责自己的字段和筛选逻辑

## 组件拆分

### 公共模板

- `src/main/resources/templates/fragments/common/filter-active-tags.html`
  - 通用“已激活筛选标签”区域
  - 负责渲染标签列表和“清空筛选”按钮
- `src/main/resources/templates/fragments/common/mobile_filter_drawer.html`
  - 通用移动端筛选抽屉片段
  - 负责标题、说明、底部按钮和字段区域容器
  - 通过 `#insert fields` 承载业务字段模板
- `src/main/resources/templates/fragments/common/search_dropdown_styles.html`
  - 搜索下拉样式
  - 供使用搜索下拉字段的页面继续复用

### 业务页面模板

- 页面自己的 `filter-fields.html`
  - 一份字段定义，同时给桌面端和移动端复用
- 页面自己的 `content.html`
  - 直接接入桌面端筛选区和公共移动端抽屉

## 节点页接入方式

节点页现在是首个接入方：

- `src/main/resources/templates/vpn/node/filter-fields.html`
  - 节点的业务筛选字段定义
- `src/main/resources/templates/vpn/node/content.html`
  - 直接接入桌面端筛选字段
  - 使用公共 `mobile_filter_drawer` 片段承载移动端字段
  - 移动端激活标签区域使用公共 `filter-active-tags.html`

## JS 约定

为了让公共模板能跨页面复用，页面 JS 需要提供一组统一方法：

- `hasActiveFilters()`
  - 是否存在已激活筛选
- `getActiveFilterTags()`
  - 返回标签数组
- `clearFilter(key)`
  - 清空单个筛选
- `resetFilters()`
  - 重置全部筛选

推荐标签结构：

```js
[
  { key: 'type', label: '类型', value: '中转' },
  { key: 'status', label: '状态', value: '已启用' }
]
```

## 复用步骤

如果其他页面要接入这套组件，建议按下面做：

1. 新建页面自己的 `filter-fields.html`。
2. 在页面 `content.html` 中直接引入这份字段片段作为桌面端筛选区。
3. 在同一个 `content.html` 中通过 `#include fragments/common/mobile_filter_drawer` 接入公共抽屉。
4. 在 `#fields` section 中传入当前页面的移动端筛选字段。
5. 在页面主内容模板中引入公共 `fragments/common/filter-active-tags.html`。
6. 在页面 JS 中实现统一的筛选标签和重置方法。

## 适用边界

这套组件适合：

- 列表页筛选
- 桌面端内联筛选 + 移动端抽屉筛选
- 需要展示已激活筛选标签的场景

如果页面需要完全不同的交互，例如多步筛选、复杂联动表单或高级查询面板，建议在这套公共壳子之外单独扩展。
