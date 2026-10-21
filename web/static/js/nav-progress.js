// 慢网络下给跳转即时反馈：点击站内链接或提交表单后显示顶部进度条，并让触发按钮进入加载态。
(function () {
  var bar, btn, timer;

  function start(el) {
    if (el && el.classList.contains('btn')) { btn = el; btn.classList.add('btn-loading'); }
    // 快速响应时不闪烁进度条
    timer = setTimeout(function () {
      bar = document.createElement('div');
      bar.className = 'progress nav-progress';
      bar.setAttribute('role', 'progressbar');
      bar.setAttribute('aria-label', '页面加载中');
      bar.innerHTML = '<div class="progress-bar progress-bar-indeterminate"></div>';
      document.body.appendChild(bar);
    }, 150);
  }

  function reset() {
    clearTimeout(timer);
    if (bar) { bar.remove(); bar = null; }
    if (btn) { btn.classList.remove('btn-loading'); btn = null; }
  }

  document.addEventListener('click', function (e) {
    var a = e.target.closest('a[href]');
    if (!a || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) { return; }
    if (a.target || a.hasAttribute('download') || a.hasAttribute('data-bs-toggle')) { return; }
    var url = new URL(a.href, location.href);
    if (url.origin !== location.origin || (url.pathname === location.pathname && url.search === location.search)) { return; }
    reset();
    start(a);
  });

  document.addEventListener('submit', function (e) {
    // 被 confirm() 取消或由脚本自行处理的提交不算跳转
    if (e.defaultPrevented || e.target.target) { return; }
    reset();
    start(e.submitter || e.target.querySelector('[type=submit]'));
  });

  // 浏览器后退命中 bfcache 时恢复原状
  window.addEventListener('pageshow', reset);
})();
