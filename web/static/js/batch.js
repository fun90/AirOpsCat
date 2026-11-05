// 账户和节点共用批量选择；业务表单决定操作，提交时写入当前所选 ID。
window.AirOpsPage.register(() => {
  const bar = document.querySelector('[data-batch-kind]');
  if (!bar) return;
  const kind = bar.dataset.batchKind;
  const selector = `[data-${kind}-select]`;
  const boxes = () => [...document.querySelectorAll(selector)];
  const all = document.querySelector(`[data-${kind}-select-all]`);
  const sync = () => {
    const items = boxes();
    const n = items.filter(b => b.checked).length;
    document.querySelectorAll('[data-batch-count]').forEach(e => { e.textContent = n; });
    bar.classList.toggle('d-none', n === 0);
    document.body.style.paddingBottom = n ? `${bar.offsetHeight}px` : '';
    if (all) all.checked = n > 0 && n === items.length;
  };
  document.querySelector('.page-wrapper').addEventListener('change', e => {
    if (e.target === all) boxes().forEach(b => { b.checked = all.checked; });
    if (e.target === all || e.target.matches(selector)) sync();
  });
  const list = document.querySelector(`.${kind}-list`);
  const mode = document.querySelector(`[data-${kind}-select-mode]`);
  const setMode = on => {
    list.classList.toggle(`${kind}-selecting`, on);
    mode?.classList.toggle('active', on);
    mode?.setAttribute('aria-pressed', on);
    if (!on) { boxes().forEach(b => { b.checked = false; }); sync(); }
  };
  mode?.addEventListener('click', () => setMode(!list.classList.contains(`${kind}-selecting`)));
  bar.querySelector('[data-batch-clear]').addEventListener('click', () => setMode(false));
  // 节点批量部署位弹窗的服务器过滤。
  document.querySelector('[data-server-filter]')?.addEventListener('input', e => {
    const key = e.target.value.trim().toLowerCase();
    e.target.nextElementSibling.querySelectorAll('.list-group-item').forEach(item => {
      item.classList.toggle('d-none', key !== '' && !item.textContent.toLowerCase().includes(key));
    });
  });
  document.querySelectorAll('[data-batch-form]').forEach(form => form.addEventListener('submit', () => {
    form.querySelectorAll(`input[name=${kind}_ids]`).forEach(input => input.remove());
    boxes().filter(b => b.checked).forEach(b => {
      form.append(Object.assign(document.createElement('input'), { type: 'hidden', name: `${kind}_ids`, value: b.value }));
    });
  }));
  sync();
  return () => { document.body.style.paddingBottom = ''; };
});
