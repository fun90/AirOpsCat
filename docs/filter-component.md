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

### 公共 JS

- `src/main/resources/META-INF/resources/static/js/common/responsive-filters.js`
  - 通用响应式筛选行为封装
  - 负责筛选计数、激活标签、单项清除、整组重置
  - 页面只需要传入默认值、标签生成规则和少量 hook

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
- `src/main/resources/META-INF/resources/static/js/vpn/node.js`
  - 通过 `responsive-filters.js` 接入公共筛选行为
  - 仅保留节点页特有的服务器搜索同步和标签文案逻辑

## 已接入页面

目前以下页面已切换到公共筛选组件：

- `src/main/resources/templates/vpn/node/content.html`
- `src/main/resources/templates/person/user/content.html`
- `src/main/resources/templates/person/account/content.html`
- `src/main/resources/templates/person/account-traffic/content.html`
- `src/main/resources/templates/device/server/content.html`
- `src/main/resources/templates/vpn/route-rule/content.html`
- `src/main/resources/templates/money/transactions/content.html`

## JS 约定

为了让公共模板能跨页面复用，页面 JS 不再自己重复实现整套筛选方法，而是通过 `responsive-filters.js` 生成。

页面通常只需要提供：

- 默认筛选值
- 标签生成规则
- 可选 hook
  - `applyFilters`
  - `onReset`
  - `onClear`

推荐标签结构：

```js
[
  { key: 'type', label: '类型', value: '中转' },
  { key: 'status', label: '状态', value: '已启用' }
]
```

推荐接入方式：

```js
import { createResponsiveFilterMethods } from '/static/js/common/responsive-filters.js';

const DEFAULT_FILTERS = Object.freeze({
  status: '',
  type: ''
});

methods: {
  ...createResponsiveFilterMethods({
    createDefaultFilters: () => ({ ...DEFAULT_FILTERS }),
    getActiveTags() {
      const tags = this.searchQuery ? [{
        key: 'search',
        label: '搜索',
        value: this.searchQuery
      }] : [];

      if (this.filters.status) {
        tags.push({ key: 'status', label: '状态', value: this.filters.status });
      }

      if (this.filters.type) {
        tags.push({ key: 'type', label: '类型', value: this.filters.type });
      }

      return tags;
    }
  }),
  // 页面自己的其他方法
}
```

## 复用步骤

如果其他页面要接入这套组件，建议按下面做：

1. 新建页面自己的 `filter-fields` 片段，支持 `layout='desktop'` 和 `layout='mobile'`。
2. 在页面 `content.html` 中直接引入这份字段片段作为桌面端筛选区。
3. 在同一个 `content.html` 中通过 `#include fragments/common/mobile_filter_drawer` 接入公共抽屉。
4. 在 `#fields` section 中传入当前页面的移动端筛选字段。
5. 在页面主内容模板中引入公共 `fragments/common/filter-active-tags.html`。
6. 在页面 JS 中通过 `responsive-filters.js` 接入公共筛选行为。
7. 只补充页面特有的标签文案、搜索联动或额外重置逻辑。

## 适用边界

这套组件适合：

- 列表页筛选
- 桌面端内联筛选 + 移动端抽屉筛选
- 需要展示已激活筛选标签的场景

如果页面需要完全不同的交互，例如多步筛选、复杂联动表单或高级查询面板，建议在这套公共壳子之外单独扩展。
