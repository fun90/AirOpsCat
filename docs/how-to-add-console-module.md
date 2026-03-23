# 如何添加功能模块

本文档说明在当前重构后的结构下，如何为后台控制台新增一个功能模块，并尽量减少前后端改动。

适用范围：

- 控制台页面路由形如 `/console/{module}/{page}`
- 页面使用 Qute 模板 + Tabler UI + petite-vue
- 页面脚本通过 `layout.html` 自动加载 `/static/js{uri}.js`

## 一、当前模块结构

一个控制台模块通常由以下几部分组成：

1. 页面注册信息
2. Qute 模板目录
3. 前端 JS 脚本
4. 后端 API / Service / Repository

### 1. 页面注册信息

控制台页面元数据统一维护在：

- [src/main/java/com/fun90/airopscat/service/ConsolePageRegistry.java](/C:/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/ConsolePageRegistry.java)

这里定义了：

- 菜单分组 key `moduleKey`
- 菜单分组名称 `moduleTitle`
- 菜单分组顺序 `moduleOrder`
- 菜单分组图标 key `moduleIconKey`
- 页面标题 `title`
- 菜单显示名称 `menuTitle`
- 菜单排序 `menuOrder`
- 页面副标题 `secondaryTitle`
- 页面路由 `uri`
- 页面内容模板 `contentTemplate`
- 是否在左侧菜单显示 `showInMenu`
- 是否显示右上角新增按钮 `showAddButton`
- 新增按钮文案 `buttonText`
- 弹窗前缀 `modalIdPrefix`

说明：

- 左侧菜单已经由注册表自动生成
- 新增模块时不再需要单独修改 `sidebar.html`

### 2. 页面渲染入口

控制台统一入口在：

- [src/main/java/com/fun90/airopscat/controller/HomeController.java](/C:/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/HomeController.java)

`HomeController` 会根据 `ConsolePageRegistry` 的配置动态渲染页面模板，并把菜单分组数据一起传给 layout。

### 3. 模板目录

模板已经按业务分目录管理，示例：

- `src/main/resources/templates/person/user/`
- `src/main/resources/templates/device/server/`
- `src/main/resources/templates/vpn/node/`

典型目录结构：

```text
src/main/resources/templates/vpn/route-rule/
  content.html
  filters.html
  stats.html
  table.html
  form.html
  edit-form.html
  modals.html
```

说明：

- `content.html` 是页面主体入口
- 其他文件是局部片段，由 `content.html` 或 `modals.html` 通过 `#include` 引用
- 公共分页仍然使用根目录下的 `pagination-controls.html`

### 4. 前端脚本目录

页面脚本放在：

- `src/main/resources/META-INF/resources/static/js`

并且路径要和页面 `uri` 对应。

例如：

- 页面 URI：`/vpn/route-rule`
- 自动加载脚本：`/static/js/vpn/route-rule.js`
- 对应文件：`src/main/resources/META-INF/resources/static/js/vpn/route-rule.js`

这是因为 [src/main/resources/templates/layout.html](/C:/Users/xiong/code/me/AirOpsCat/src/main/resources/templates/layout.html) 中写死了：

```html
<script type="module" src="/static/js{uri}.js"></script>
```

## 二、添加一个新模块的最少步骤

下面以新增 `vpn/protocol-template` 模块为例说明。

目标路由：

- `/console/vpn/protocol-template`

对应 URI：

- `/vpn/protocol-template`

### 步骤 1：在 `ConsolePageRegistry` 注册页面

在 `ConsolePageRegistry` 增加一条 `Map.entry(...)`：

```java
Map.entry("/vpn/protocol-template", page(
        "vpn",
        "代理",
        30,
        "vpn",
        "协议模板",
        40,
        "管理 sing-box / xray 模板",
        "/vpn/protocol-template",
        "vpn/protocol-template/content",
        true,
        true,
        "添加模板",
        "protocol-template-"
))
```

这一步同时完成两件事：

1. 注册页面渲染信息
2. 自动接入左侧菜单

约束：

- `moduleKey` 必须落在已有分组内，或者你同步扩展了新的图标 key
- `moduleTitle` 要和该分组下其他页面保持一致
- `moduleOrder` 决定分组顺序
- `menuOrder` 决定组内菜单顺序
- `uri` 必须和控制台访问地址一致
- `contentTemplate` 必须对应 Qute 模板相对路径，不带 `.html`
- `buttonText` 只有在 `showAddButton=true` 时才有意义
- `modalIdPrefix` 要和前端弹窗 id 命名保持一致

### 步骤 2：创建模板目录

新增目录：

```text
src/main/resources/templates/vpn/protocol-template/
```

最少需要一个入口模板：

```text
content.html
```

如果页面比较完整，建议按下面方式拆分：

```text
src/main/resources/templates/vpn/protocol-template/
  content.html
  filters.html
  stats.html
  table.html
  modals.html
```

`content.html` 示例：

```html
<div class="page-body">
    <div class="container-xl">
        <div class="col-12">
            <div class="card">
                <div class="card-header">
                    <div class="card-actions">
                        <div class="row g-2">
                            <div class="col">
                                <input type="text"
                                       class="form-control"
                                       placeholder="搜索模板名称..."
                                       v-model="searchQuery"
                                       @input="searchDebounced">
                            </div>
                            {#include vpn/protocol-template/filters /}
                        </div>
                    </div>
                </div>

                {#include vpn/protocol-template/stats /}

                <div class="table-responsive">
                    {#include vpn/protocol-template/table /}
                </div>

                {#include pagination-controls /}
            </div>
        </div>

        {#include vpn/protocol-template/modals /}
    </div>
</div>
```

模板引用规则：

- 同模块片段请使用完整路径，例如 `vpn/protocol-template/table`
- 公共片段继续用现有公共路径，例如 `pagination-controls`

### 步骤 3：创建前端脚本

新增文件：

```text
src/main/resources/META-INF/resources/static/js/vpn/protocol-template.js
```

建议做法：

1. 找一个交互接近的页面复制
2. 修改 API 地址、字段、弹窗 id、筛选条件
3. 保持已有的 petite-vue 与公共工具用法

比较适合参考的现有页面：

- 列表 + 弹窗 CRUD：`vpn/route-rule.js`
- 列表 + 多筛选：`vpn/node.js`
- 简单列表管理：`system/tag.js`

### 步骤 4：补后端接口

根据业务需要补充：

- Controller
- Service
- Repository
- DTO / Entity / VO

建议保持现有命名风格，例如：

- `ProtocolTemplateController`
- `ProtocolTemplateService`
- `ProtocolTemplateRepository`

如果页面是后台管理页，通常接口会放在：

- `/api/admin/...`

## 三、推荐的新增顺序

建议按下面顺序开发，出错最少：

1. 先补 `ConsolePageRegistry`
2. 创建模板目录与 `content.html`
3. 创建 `static/js/{module}/{page}.js`
4. 再开发 API 和业务逻辑
5. 最后联调弹窗、表格、筛选和分页

这样即使后端还没写完，也可以先访问页面，确认路由、模板装配和菜单生成都没问题。

## 四、一个最小可运行示例

如果只是先把页面挂出来，可以只做下面三件事：

1. 在 `ConsolePageRegistry` 注册页面
2. 新建 `content.html`
3. 新建一个最简单的 JS 文件

### 最小 `content.html`

```html
<div class="page-body">
    <div class="container-xl">
        <div class="card">
            <div class="card-body">
                <div class="empty">
                    <div class="empty-header">MODULE</div>
                    <p class="empty-title">协议模板</p>
                    <p class="empty-subtitle text-muted">
                        页面骨架已接入，后续可继续补表格、筛选和弹窗。
                    </p>
                </div>
            </div>
        </div>
    </div>
</div>
```

### 最小 `protocol-template.js`

```javascript
import { createApp } from '/static/js/petite-vue.umd.js';

createApp({
  mounted() {
    console.log('protocol-template page mounted');
  }
}).mount('#app');
```

## 五、常见坑

### 1. 只加了模板，没加注册表

表现：

- 访问 `/console/.../...` 返回 404 页面

原因：

- `ConsolePageRegistry` 没有对应 `uri`

### 2. 注册了页面，但模板路径不对

表现：

- 控制台页面打开后进入 `notFound`

原因：

- `contentTemplate` 对应的模板不存在
- 模板路径写错
- 少写了子目录

正确示例：

- `vpn/node/content`
- `person/account/content`

错误示例：

- `node-content`
- `vpn/node/content.html`

### 3. 页面打开了，但 JS 没生效

表现：

- 表格不加载
- 按钮没反应
- `v-model` / `@click` 无效果

原因通常是：

- 缺少 `/static/js{uri}.js`
- JS 文件路径与 `uri` 不一致
- JS 运行时报错

### 4. 新增按钮能显示，但点了没反应

需要检查：

- `showAddButton` 是否为 `true`
- `buttonText` 是否配置
- JS 中是否实现了 `openCreateModal`
- 模板中是否存在对应 id 的弹窗
- `modalIdPrefix` 是否和弹窗 id 约定一致

### 5. 菜单里看不到新页面

原因通常是：

- `showInMenu` 被设成了 `false`
- `moduleKey` / `moduleTitle` / `moduleOrder` 配置不完整
- `ConsolePageRegistry` 中没有注册该页面

### 6. 分组图标没显示

原因：

- `moduleIconKey` 没有对应的 sidebar 图标分支

当前已内置的图标 key：

- `person`
- `device`
- `vpn`
- `money`
- `system`

如果要新增全新的菜单分组，除了注册表外，还需要在 sidebar 图标分支中补一个新 key。

## 六、建议的模块目录约定

建议继续保持下面的命名方式：

### 模板目录

```text
templates/{module}/{page}/
```

例如：

- `templates/person/user/`
- `templates/device/server/`
- `templates/vpn/node/`

### 前端脚本

```text
static/js/{module}/{page}.js
```

例如：

- `static/js/person/user.js`
- `static/js/device/server.js`
- `static/js/vpn/node.js`

### 控制台 URI

```text
/console/{module}/{page}
```

例如：

- `/console/person/user`
- `/console/device/server`
- `/console/vpn/node`

### 页面注册模板路径

```text
{module}/{page}/content
```

例如：

- `person/user/content`
- `device/server/content`
- `vpn/node/content`

## 七、建议后续继续优化的点

这次重构已经把“页面注册”、“页面模板分发”和“侧边栏菜单”收拢到了同一套注册表。这样新增一个模块时，理论上就只需要：

1. 增加一条配置
2. 新增模板目录
3. 新增 JS
4. 新增后端接口

如果后续还想进一步收敛维护成本，可以继续把首页、顶部用户菜单等导航元数据也纳入统一配置。

## 八、自检清单

新增模块后，至少检查以下项目：

1. 能访问 `/console/{module}/{page}`
2. 左侧菜单自动出现并能进入该页面
3. 页面标题、副标题正确
4. 页面对应 JS 已加载
5. 搜索框、筛选、分页能正常初始化
6. 新增按钮显示逻辑符合预期
7. 弹窗 id 与 JS 方法匹配
8. 编译通过：`./mvnw -DskipTests compile`
