// [data-copy] 按钮：复制链接；有 data-copy-note 时附加一行说明，便于直接发给客户。
document.addEventListener('click', function (e) {
  var b = e.target.closest('[data-copy]');
  if (!b) { return; }
  var text = b.dataset.copy + (b.dataset.copyNote ? '\n' + b.dataset.copyNote : '');
  function done() {
    var i = b.querySelector('i'); if (!i) { return; }
    var old = i.className; i.className = 'ti ti-check text-success';
    setTimeout(function () { i.className = old; }, 1200);
  }
  if (navigator.clipboard && window.isSecureContext) { navigator.clipboard.writeText(text).then(done); return; }
  var t = document.createElement('textarea'); t.value = text; t.style.position = 'fixed'; t.style.opacity = '0';
  document.body.appendChild(t); t.select(); try { document.execCommand('copy'); done(); } catch (err) {} t.remove();
});
