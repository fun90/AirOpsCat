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
