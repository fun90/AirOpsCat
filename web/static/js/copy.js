// [data-copy] 按钮：复制链接；有 data-copy-note 时附加一行说明，便于直接发给客户。
document.addEventListener('click', function (e) {
  var b = e.target.closest('[data-copy]');
  if (!b) { return; }
  var text = b.dataset.copy + (b.dataset.copyNote ? '\n' + b.dataset.copyNote : '');
  function done() {
    var icon = b.querySelector('svg.coss-icon');
    var use = icon && icon.querySelector('use');
    if (!use) { return; }
    var oldHref = use.getAttribute('href');
    use.setAttribute('href', '/static/icons/lucide.svg?v=3#check');
    icon.classList.add('text-success');
    setTimeout(function () { use.setAttribute('href', oldHref); icon.classList.remove('text-success'); }, 1200);
  }
  if (navigator.clipboard && window.isSecureContext) { navigator.clipboard.writeText(text).then(done); return; }
  var t = document.createElement('textarea'); t.value = text; t.style.position = 'fixed'; t.style.opacity = '0';
  document.body.appendChild(t); t.select(); try { document.execCommand('copy'); done(); } catch (err) {} t.remove();
});
