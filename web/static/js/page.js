// invalid 不冒泡：统一在捕获阶段展开无效字段所在页签，局部导航后继续生效。
document.addEventListener('invalid', event => {
  const pane = event.target.closest('.tab-pane');
  if (pane && !pane.classList.contains('active')) {
    const trigger = document.querySelector(`[data-bs-target="#${CSS.escape(pane.id)}"]`);
    trigger?.click();
  }
}, true);

// 页面脚本注册一次；局部导航时释放旧页面资源，再初始化新内容。
window.AirOpsPage = (() => {
  const initializers = [];
  let cleanups = [];
  function run(init) {
    const cleanup = init();
    if (typeof cleanup === 'function') cleanups.push(cleanup);
  }
  return {
    register(init) { initializers.push(init); run(init); },
    mount() { initializers.forEach(run); },
    unmount() {
      const previous = cleanups;
      cleanups = [];
      previous.reverse().forEach(cleanup => cleanup());
    }
  };
})();
