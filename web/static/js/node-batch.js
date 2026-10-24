(() => {
  const bar = document.getElementById('nodeBatchBar');
  if (!bar) return;
  const boxes = () => [...document.querySelectorAll('[data-node-select]')];
  const all = document.querySelector('[data-node-select-all]');
  const sync = () => {
    const n = boxes().filter((b) => b.checked).length;
    document.querySelectorAll('[data-batch-count]').forEach((e) => { e.textContent = n; });
    bar.classList.toggle('d-none', n === 0);
    if (all) all.checked = n > 0 && n === boxes().length;
  };
  document.addEventListener('change', (e) => {
    if (e.target === all) boxes().forEach((b) => { b.checked = all.checked; });
    if (e.target === all || e.target.matches('[data-node-select]')) sync();
  });
  bar.querySelector('[data-batch-clear]').addEventListener('click', () => {
    boxes().forEach((b) => { b.checked = false; });
    sync();
  });
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
})();
