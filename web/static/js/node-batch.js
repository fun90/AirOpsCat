window.AirOpsPage.register(() => {
  const bar = document.getElementById('nodeBatchBar');
  if (!bar) return;
  const boxes = () => [...document.querySelectorAll('[data-node-select]')];
  const all = document.querySelector('[data-node-select-all]');
  const sync = () => {
    const n = boxes().filter((b) => b.checked).length;
    document.querySelectorAll('[data-batch-count]').forEach((e) => { e.textContent = n; });
    bar.classList.toggle('d-none', n === 0);
    // 给页面底部留出浮层高度，避免遮住最后几行和分页
    document.body.style.paddingBottom = n ? `${bar.offsetHeight}px` : '';
    if (all) all.checked = n > 0 && n === boxes().length;
  };
  document.querySelector('.page-wrapper').addEventListener('change', (e) => {
    if (e.target === all) boxes().forEach((b) => { b.checked = all.checked; });
    if (e.target === all || e.target.matches('[data-node-select]')) sync();
  });
  const clear = () => {
    boxes().forEach((b) => { b.checked = false; });
    sync();
  };
  // 移动端批量选择模式：退出时清空已选，避免隐藏的勾选框仍带着选中状态
  const list = document.querySelector('.node-list');
  const mode = document.querySelector('[data-node-select-mode]');
  const setMode = (on) => {
    list.classList.toggle('node-selecting', on);
    mode?.classList.toggle('active', on);
    mode?.setAttribute('aria-pressed', on);
    if (!on) clear();
  };
  mode?.addEventListener('click', () => setMode(!list.classList.contains('node-selecting')));
  bar.querySelector('[data-batch-clear]').addEventListener('click', () => setMode(false));
  document.querySelectorAll('[data-batch-form]').forEach((form) => form.addEventListener('submit', () => {
    form.querySelectorAll('input[name=node_ids]').forEach((e) => e.remove());
    boxes().filter((b) => b.checked).forEach((b) => {
      const i = document.createElement('input');
      i.type = 'hidden';
      i.name = 'node_ids';
      i.value = b.value;
      form.append(i);
    });
  }));
  sync();
  return () => { document.body.style.paddingBottom = ''; };
});
