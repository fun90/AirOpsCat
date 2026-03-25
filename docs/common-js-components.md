# 公共 JS 组件使用文档

本文档整理 `src/main/resources/META-INF/resources/static/js/common` 下的公共组件，方便在切换电脑、重新开新会话，或由其他 AI Agent 接手时快速理解并复用。

## 组件清单

| 组件 | 文件 | 配套模板 / 样式 | 作用 |
| --- | --- | --- | --- |
| 日期时间工具 | `src/main/resources/META-INF/resources/static/js/common/common.js` | 无 | 统一处理页面中的北京时间格式化与相对时间显示 |
| 列表基类 | `src/main/resources/META-INF/resources/static/js/common/data-table.js` | 无 | 为后台列表页提供分页、搜索、CRUD、Modal、工具方法 |
| 响应式筛选 | `src/main/resources/META-INF/resources/static/js/common/responsive-filters.js` | `templates/fragments/common/filter-active-tags.html`、`templates/fragments/common/mobile_filter_drawer.html` | 公共筛选能力，详细文档直接见 `docs/filter-component.md` |
| 搜索下拉 | `src/main/resources/META-INF/resources/static/js/common/search-dropdown.js` | `templates/fragments/common/search_dropdown_component.html`、`templates/fragments/common/search_dropdown_styles.html` | 远程搜索并从候选项中选择实体 |
| Toast 通知 | `src/main/resources/META-INF/resources/static/js/common/toast-utils.js` | 无 | Tabler 风格通知、加载中提示 |
| 文本域自动高度 | `src/main/resources/META-INF/resources/static/js/common/autosize.js`、`autosize.min.js` | 无 | 自动撑高 textarea |
| Modal 中的 autosize 管理 | `src/main/resources/META-INF/resources/static/js/common/modal-autosize.js` | 无 | 处理 Modal / Tab 内 textarea 自动高度初始化与销毁 |

## 全局加载约定

公共基础脚本已在 [`layout.html`](/Users/xiong/code/me/AirOpsCat/src/main/resources/templates/layout.html#L468) 统一引入：

- `/static/js/petite-vue.umd.js`
- `/static/js/common/autosize.min.js`
- `/static/js/common/modal-autosize.js`
- `/static/js/common/toast-utils.js`
- 当前页面自己的 `/static/js{uri}.js`

这意味着：

- `PetiteVue` 在页面脚本中可直接使用
- `ToastUtils` 会挂到 `window.ToastUtils`
- `autosize` 会挂到全局对象
- 列表页脚本通常只需要显式 `import` 自己依赖的公共模块

## 1. 日期时间工具

文件：[`common.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/common/common.js)

### 导出方法

- `formatDateTimeForLocal(date)`
  - 把 `Date` 转成 `YYYY-MM-DDTHH:mm`
  - 用于 `input[type="datetime-local"]`
  - 逻辑上固定按北京时间 `UTC+8` 处理
- `formatDateTimeForDisplay(dateTime)`
  - 把字符串或 `Date` 格式化成 `YYYY-MM-DD HH:mm:ss`
- `formatRelativeTime(dateTime)`
  - 输出“刚刚 / X分钟前 / X小时前 / X天前”
  - 超过 7 天时回退为完整时间

### 当前使用页面

- [`person/account.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/person/account.js)
- [`money/transactions.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/money/transactions.js)

### 典型用法

```js
import { formatDateTimeForLocal, formatRelativeTime } from '/static/js/common/common.js';

const now = new Date();
this.newItem.transactionDate = formatDateTimeForLocal(now);
this.createTimeText = formatRelativeTime(record.createTime);
```

### 适用场景

- 新建 / 编辑表单默认时间
- 续期时间快捷填充
- 列表中的创建时间、最近活跃时间展示

## 2. 列表基类 DataTable

文件：[`data-table.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/common/data-table.js)

`DataTable` 是后台列表页的公共基类。它不是完整页面，而是“PetiteVue 页面状态 + 通用方法”的封装。

### 已内置的能力

- 首次挂载后自动 `fetchRecords()`
- 分页：上一页、下一页、跳页、切换页大小
- 搜索防抖：`searchDebounced()`
- CRUD：创建、编辑、删除、状态切换
- Modal 打开 / 关闭
- Tooltip 重新初始化
- 常用工具方法
  - `formatDate`
  - `formatDateTime`
  - `formatBytes`
  - `copyToClipboard`
  - `uuidv4`
  - `generateRandomString`

### 默认数据结构

组件会提供这些常用状态：

- `records`
- `searchQuery`
- `filters`
- `currentPage`
- `pageSize`
- `totalItems`
- `totalPages`
- `selectedItem`
- `newItem`
- `editedItem`
- `validationErrors`
- `loading`

### 页面侧通常需要补充的内容

- `entityName`
- `modalIdPrefix`
- `filters` 默认值
- `initialize()`
- `getApiUrl()`
- `validateCreateForm()` / `validateEditForm()`
- `prepareCreateData()` / `prepareUpdateData()`
- `resetCreateForm()`
- `prepareEditForm(item)`

按业务复杂度，也可以补：

- `afterFetch(data)`
- `afterCreate(data)`
- `afterUpdate(data)`
- `getDeleteUrl(item)`
- `getToggleStatusUrl(item, action)`
- `updateLocalItem(data)`
- `updateItemStatus(item, data)`

### 初始化方式

```js
import { DataTable } from '/static/js/common/data-table.js';

const table = new DataTable({
    data: {
        entityName: 'users',
        modalIdPrefix: 'user-',
        filters: {
            status: ''
        },
        newItem: {
            name: ''
        }
    },
    methods: {
        initialize() {
            this.loadOptions();
        },
        getApiUrl() {
            return '/api/admin/users';
        },
        validateCreateForm() {
            return !!this.newItem.name;
        },
        prepareCreateData() {
            return {
                name: this.newItem.name
            };
        }
    }
});

table.createApp('#app');
```

### 接口返回约定

`fetchRecords()` 默认假定接口返回结构包含：

- `records`: 当前页数据
- `total`: 总条数
- `pages`: 总页数
- `current`: 当前页
- `stats`: 可选，若存在会挂到 `this.stats`

### 现有接入页面

- [`device/domain.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/device/domain.js)
- [`device/server.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/device/server.js)
- [`money/transactions.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/money/transactions.js)
- [`person/account-traffic.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/person/account-traffic.js)
- [`person/account.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/person/account.js)
- [`person/user.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/person/user.js)
- [`system/backup.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/system/backup.js)
- [`system/tag.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/system/tag.js)
- [`vpn/node.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/vpn/node.js)
- [`vpn/route-rule.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/vpn/route-rule.js)
- [`vpn/server-config.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/vpn/server-config.js)

## 3. 响应式筛选组件

文件：[`responsive-filters.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/common/responsive-filters.js)

配套模板：

- [`filter-active-tags.html`](/Users/xiong/code/me/AirOpsCat/src/main/resources/templates/fragments/common/filter-active-tags.html)
- [`mobile_filter_drawer.html`](/Users/xiong/code/me/AirOpsCat/src/main/resources/templates/fragments/common/mobile_filter_drawer.html)

这部分仓库里已经有专门文档，后续直接以它为准，不在本文重复展开：

- [`docs/filter-component.md`](/Users/xiong/code/me/AirOpsCat/docs/filter-component.md)

如果只想快速找现有接入样例，可以看：

- [`vpn/node.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/vpn/node.js)
- [`person/account.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/person/account.js)
- [`money/transactions.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/money/transactions.js)

## 4. 搜索下拉组件

文件：[`search-dropdown.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/common/search-dropdown.js)

配套模板 / 样式：

- [`search_dropdown_component.html`](/Users/xiong/code/me/AirOpsCat/src/main/resources/templates/fragments/common/search_dropdown_component.html)
- [`search_dropdown_styles.html`](/Users/xiong/code/me/AirOpsCat/src/main/resources/templates/fragments/common/search_dropdown_styles.html)

### 组件用途

远程搜索后展示候选结果，用户选中后把结果写回表单字段。常见于：

- 选择用户
- 选择账号
- 选择域名
- 选择服务器

### 创建方式

```js
import { createSearchDropdown } from '/static/js/common/search-dropdown.js';

this.userSearch = createSearchDropdown({
    apiUrl: '/api/admin/users',
    placeholder: '请输入用户',
    formatItem: (item) => ({
        id: item.id,
        name: item.nickName,
        data: item
    }),
    onSelect: (item) => {
        this.newItem.userId = item.id;
    },
    onChange: (text, item) => {
        if (!item) {
            this.newItem.userId = '';
        }
    }
});

this.userSearch.bindToDOM('userSearch');
```

### 模板接入

在页面模板中引入组件片段：

```html
{#include fragments/common/search_dropdown_component componentVar='userSearch' cssClass='' /}
```

组件实际会依赖以下 DOM id：

- `search-dropdown-{componentVar}`
- `search-input-{componentVar}`
- `search-clear-{componentVar}`
- `search-menu-{componentVar}`
- `search-loading-{componentVar}`
- `search-results-{componentVar}`
- `search-no-results-{componentVar}`

因此 `bindToDOM('userSearch')` 必须和模板里的 `componentVar='userSearch'` 保持一致。

### 主要配置项

- `apiUrl`
- `searchParam`
  - 默认 `search`
- `sizeParam`
  - 默认 `size`
- `defaultSize`
  - 默认 `20`
- `minQueryLength`
  - 默认 `2`
  - 可以设为 `0`，例如服务器筛选支持点开即选
- `debounceDelay`
  - 默认 `300ms`
- `placeholder`
- `formatItem`
  - 把接口返回项转换为前端显示项
- `formatDisplay`
  - 指定选中后输入框显示文本
- `onSelect`
- `onChange`
- `enableCache`
  - 默认开启
- `cacheExpiration`
  - 默认 `5` 分钟
- `defaultOptions`
  - 默认选项，未输入搜索内容时显示
  - 可以是数组或返回数组的函数
  - 可选配置
- `multiSelect`
  - 是否支持多选
  - 默认 `false`
  - 可选配置

### 实例方法

- `bindToDOM(componentId)`
- `setValue(text, item = null)`
- `getValue()`
- `clear()`
- `destroy()`
- `updateUI()`

### 预设 Presets

文件里内置了三个快捷预设：

- `SearchDropdownPresets.account()`
- `SearchDropdownPresets.domain()`
- `SearchDropdownPresets.server()`

适合“业务类型切换后动态替换数据源”的场景。当前 [`money/transactions.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/money/transactions.js) 就是这样接的。

### 当前接入页面

- [`person/account/modals.html`](/Users/xiong/code/me/AirOpsCat/src/main/resources/templates/person/account/modals.html)
- [`person/account-traffic/modals.html`](/Users/xiong/code/me/AirOpsCat/src/main/resources/templates/person/account-traffic/modals.html)
- [`money/transactions/modals.html`](/Users/xiong/code/me/AirOpsCat/src/main/resources/templates/money/transactions/modals.html)
- [`vpn/node/filter-fields.html`](/Users/xiong/code/me/AirOpsCat/src/main/resources/templates/vpn/node/filter-fields.html)

对应 JS：

- [`person/account.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/person/account.js)
- [`person/account-traffic.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/person/account-traffic.js)
- [`money/transactions.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/money/transactions.js)
- [`vpn/node.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/vpn/node.js)

### 接入注意事项

- 先渲染模板，再 `bindToDOM()`，所以现有页面通常在 `initializeSearchComponents()` 中 `setTimeout(..., 100)` 后绑定
- `apiUrl` 为空时不会发起搜索
- 默认从响应体读取 `data.records`
- 选中后输入框显示的是 `formatDisplay(item)` 或 `item.name`
- 如果输入框被清空，通常需要在 `onChange` 中同步把关联表单字段清空

### 默认选项用法

```js
// 静态默认选项
this.statusSearch = createSearchDropdown({
    placeholder: '选择状态',
    defaultOptions: [
        {id: 'active', name: '激活'},
        {id: 'inactive', name: '未激活'},
        {id: 'expired', name: '已过期'}
    ],
    onSelect: (item) => {
        this.filters.status = item.id;
    }
});

// 动态默认选项
this.serverSearch = createSearchDropdown({
    apiUrl: '/api/admin/servers',
    placeholder: '搜索服务器',
    minQueryLength: 2,
    defaultOptions: () => {
        return this.recentServers || [];
    },
    formatItem: (item) => ({
        id: item.id,
        name: `${item.ip} (${item.name})`,
        data: item
    }),
    onSelect: (item) => {
        this.selectedServer = item.data;
    }
});
```

### 多选用法

```js
this.tagSearch = createSearchDropdown({
    apiUrl: '/api/admin/tags',
    placeholder: '选择标签',
    multiSelect: true,
    formatItem: (item) => ({
        id: item.id,
        name: item.name,
        data: item
    }),
    onSelect: (items) => {
        // items 是已选中项的数组
        this.selectedTagIds = items.map(i => i.id);
    },
    onChange: (text, items) => {
        // 实时更新
        this.selectedTagIds = items.map(i => i.id);
    }
});

this.tagSearch.bindToDOM('tagSearch');
```

## 5. ToastUtils 通知组件

文件：[`toast-utils.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/common/toast-utils.js)

该组件在加载后会挂到 `window.ToastUtils`，页面脚本通常直接使用，不需要再次 import。

### 可用方法

- `ToastUtils.show(title, message, type, delay = 3000)`
- `ToastUtils.success(message, title = 'Success')`
- `ToastUtils.error(message, title = 'Error')`
- `ToastUtils.warning(message, title = 'Warning')`
- `ToastUtils.info(message, title = 'Info')`
- `ToastUtils.loading(title, message)`
  - 返回 `{ hide() }`
- `ToastUtils.clear()`

### 典型用法

```js
ToastUtils.show('Success', '创建成功', 'success');

const loadingToast = ToastUtils.loading('部署中', '节点正在部署，请稍候...');
fetch('/api/admin/nodes/1/deploy', { method: 'POST' })
    .finally(() => {
        loadingToast.hide();
    });
```

### 特点

- 自动创建 `#toast-container`
- 关闭后自动从 DOM 移除
- `loading()` 默认不自动隐藏、不可手动关闭

### 当前使用情况

`DataTable` 内部会直接调用 `ToastUtils` 处理通用成功 / 失败通知。除此之外，多个业务页面也会在自定义操作中直接调用，例如：

- [`person/account.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/person/account.js)
- [`vpn/node.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/vpn/node.js)

## 6. autosize 与 ModalAutosizeHandler

文件：

- [`autosize.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/common/autosize.js)
- [`autosize.min.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/common/autosize.min.js)
- [`modal-autosize.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/common/modal-autosize.js)

### 分工

- `autosize.js`
  - 第三方 autosize 库源码
- `autosize.min.js`
  - 当前在页面里实际加载的压缩版
- `modal-autosize.js`
  - 项目自己的封装，负责处理 Modal / Tab 中 textarea 的初始化与销毁

### 页面中的默认行为

[`layout.html`](/Users/xiong/code/me/AirOpsCat/src/main/resources/templates/layout.html#L469) 已经做了两层处理：

- 页面加载后，对普通区域的 `textarea[data-bs-toggle="autosize"]` 调 `autosize(...)`
- 当 `shown.bs.modal`、`shown.bs.tab`、`hidden.bs.modal` 触发时，由 `ModalAutosizeHandler` 管理 Modal / Tab 内部文本域

### 使用方式

只需要在文本域上加属性：

```html
<textarea class="form-control" data-bs-toggle="autosize"></textarea>
```

### 额外能力

`modal-autosize.js` 还暴露了两个全局入口：

- `window.modalAutosizeHandler`
- `window.manualInitAutosize()`

当某些 DOM 是延迟插入且不走标准 Modal 生命周期时，可以手动触发。

### 注意事项

- 组件依赖全局 `autosize`
- 如果 textarea 已初始化，再次进入 Modal 时会先 `destroy` 再重新初始化，避免重复绑定

## 7. 组件之间的常见组合

### 列表页标准组合

最常见的后台列表页会同时用到：

1. `DataTable`
2. `createResponsiveFilterMethods`
3. `ToastUtils`
4. 某些页面再叠加 `SearchDropdown`

典型页面可以参考：

- [`vpn/node.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/vpn/node.js)
- [`person/account.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/person/account.js)
- [`money/transactions.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/money/transactions.js)

### 新增一个列表页时的推荐顺序

1. 先用 `DataTable` 搭起分页、搜索、CRUD 主体
2. 如果页面有桌面端 / 移动端筛选，直接按 [`docs/filter-component.md`](/Users/xiong/code/me/AirOpsCat/docs/filter-component.md) 接入 `responsive-filters.js`
3. 如果筛选项或表单项需要远程选实体，再接 `search-dropdown.js`
4. 如果表单里有多行文本，直接给 `textarea` 加 `data-bs-toggle="autosize"`
5. 业务操作中的成功 / 失败提示统一走 `ToastUtils`

## 8. 已知约定和限制

- 公共组件整体基于 `PetiteVue + Tabler + Bootstrap 5` 事件模型
- `DataTable` 默认假设后台分页接口返回 `records / total / pages / current`
- `SearchDropdown` 默认假设搜索接口返回 `records`
- `ToastUtils` 是全局对象，不是 ES module 默认导出
- `common.js` 当前时间处理固定按北京时间 `UTC+8`
- `search_dropdown_styles.html` 需要页面显式 include；JS 本身不会自动注入样式

## 9. 推荐阅读顺序

如果是第一次接手这套前端公共能力，建议按下面顺序读：

1. [`data-table.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/common/data-table.js)
2. [`docs/filter-component.md`](/Users/xiong/code/me/AirOpsCat/docs/filter-component.md)
3. [`search-dropdown.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/common/search-dropdown.js)
4. [`toast-utils.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/common/toast-utils.js)
5. [`common.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/common/common.js)
6. [`modal-autosize.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/common/modal-autosize.js)

再结合以下业务页面读一遍，会最快进入状态：

- [`vpn/node.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/vpn/node.js)
- [`person/account.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/person/account.js)
- [`money/transactions.js`](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/money/transactions.js)
