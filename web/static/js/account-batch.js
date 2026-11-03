// 账户列表批量选择：勾选后显示底部操作条，提交时把所选账户 ID 写进表单。
window.AirOpsPage.register(() => {
  const bar = document.getElementById('accountBatchBar');
  if (!bar) return;
  const boxes = () => [...document.querySelectorAll('[data-account-select]')];
  const all = document.querySelector('[data-account-select-all]');
  const sync = () => {
    const n = boxes().filter((b) => b.checked).length;
    document.querySelectorAll('[data-batch-count]').forEach((e) => { e.textContent = n; });
    bar.classList.toggle('d-none', n === 0);
    document.body.style.paddingBottom = n ? `${bar.offsetHeight}px` : '';
    all.checked = n > 0 && n === boxes().length;
  };
  document.querySelector('.page-wrapper').addEventListener('change', (e) => {
    if (e.target === all) boxes().forEach((b) => { b.checked = all.checked; });
    if (e.target === all || e.target.matches('[data-account-select]')) sync();
  });
  // 移动端批量选择模式：退出时清空已选，避免隐藏的勾选框仍带着选中状态
  const table = document.querySelector('.account-list');
  const mode = document.querySelector('[data-account-select-mode]');
  const setMode = (on) => {
    table.classList.toggle('account-selecting', on);
    mode?.classList.toggle('active', on);
    mode?.setAttribute('aria-pressed', on);
    if (!on) { boxes().forEach((b) => { b.checked = false; }); sync(); }
  };
  mode?.addEventListener('click', () => setMode(!table.classList.contains('account-selecting')));
  bar.querySelector('[data-batch-clear]').addEventListener('click', () => setMode(false));
  document.querySelectorAll('[data-batch-form]').forEach((form) => form.addEventListener('submit', () => {
    form.querySelectorAll('input[name=account_ids]').forEach((i) => i.remove());
    boxes().filter((b) => b.checked).forEach((b) => {
      const i = document.createElement('input');
      i.type = 'hidden';
      i.name = 'account_ids';
      i.value = b.value;
      form.append(i);
    });
  }));
  sync();
  return () => { document.body.style.paddingBottom = ''; };
});
