# UI 开发与验证

## 设计原则

- 统一使用 `coss.com/ui` 视觉与交互体系：样式定义在 `web/static/css/coss.css`（设计令牌、栅格/工具类与基础组件），业务专属微调写在 `web/static/css/app.css`；交互原语由 `web/static/js/coss-ui.js` 提供，图标统一使用 `web/static/icons/lucide.svg`（Lucide SVG sprite）。新增模板类须已在样式表中定义，`web/assets_test.go` 会校验遗漏。
- 行内 `style` 仅用于运行时动态值（如标签颜色、进度条百分比）或一次性尺寸约束。
- 不加多余的副标题、说明文字；靠布局表达。
- PC 列表操作达到 5 个、移动端达到 2 个才使用下拉；移动端按 390px 检查换行与横向溢出，截图工具见 [本地验收](local-acceptance.md)。

## 局部导航验证

修改导航、返回/取消逻辑（`back.js`）、页面脚本生命周期或公共布局时，运行浏览器回归：

```bash
node --test scripts/nav-progress.test.mjs scripts/back.test.mjs
```

需要 Node.js 22+ 和 Chrome，无需安装 npm 依赖。默认使用 macOS 的 Chrome 路径；其他环境通过 `CHROME_BIN` 指定 Chrome/Chromium 可执行文件。确认测试实际执行且全部通过；未找到浏览器时会标记跳过，不能作为验证通过。
