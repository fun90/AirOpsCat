# UI 开发与验证

## 设计原则

- 不造轮子：先用 Tabler/Bootstrap 现成组件与工具类（`status-dot`、`text-break`、`text-wrap`、`cursor-pointer`、`badge`、`progress` 等），确认没有再写 `web/static/css/app.css` 或行内 `style`。新增自定义样式须能说明 Tabler 为何不能实现。Tabler CSS 已按源码裁剪，新用的类或图标须重跑 `scripts/purge-css.sh`，`web/assets_test.go` 会报出遗漏。
- 行内 `style` 仅用于运行时动态值（如标签颜色）或 Tabler 没有对应工具类的一次性尺寸。
- 不加多余的副标题、说明文字；靠布局表达。
- PC 列表操作达到 5 个、移动端达到 2 个才使用下拉；移动端按 390px 检查消息换行与横向溢出，截图工具见 [本地验收](local-acceptance.md)。

## 局部导航验证

修改导航、页面脚本生命周期或公共布局时，运行浏览器回归：

```bash
node --test scripts/nav-progress.test.mjs
```

需要 Node.js 22+ 和 Chrome，无需安装 npm 依赖。默认使用 macOS 的 Chrome 路径；其他环境通过 `CHROME_BIN` 指定 Chrome/Chromium 可执行文件。确认测试实际执行且全部通过；未找到浏览器时会标记跳过，不能作为验证通过。
