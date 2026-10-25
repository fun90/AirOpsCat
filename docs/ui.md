# UI 设计原则

- 不造轮子：先用 Tabler/Bootstrap 现成组件与工具类（`status-dot`、`text-break`、`text-wrap`、`cursor-pointer`、`badge`、`progress` 等），确认没有再写 `web/static/css/app.css` 或行内 `style`。新增自定义样式须能说明 Tabler 为何不能实现。
- 行内 `style` 仅用于运行时动态值（如标签颜色）或 Tabler 没有对应工具类的一次性尺寸。
- 不加多余的副标题、说明文字；靠布局表达。
- PC 列表操作达到 5 个、移动端达到 2 个才使用下拉；移动端按 390px 检查消息换行与横向溢出。
