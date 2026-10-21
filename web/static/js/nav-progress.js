// 按钮立即反馈点击，较慢的页面跳转再显示加载提示。
(function () {
  var bar, btn, timer;

  function start(el) {
    if (el && el.classList.contains('btn')) { btn = el; btn.classList.add('btn-loading'); }
    // 常规跳转只保留按钮反馈，避免全局提示刚出现就被新页面切掉。
    timer = setTimeout(function () {
      bar = document.createElement('div');
      bar.className = 'nav-progress';
      bar.setAttribute('role', 'status');
      bar.innerHTML = '<span class="spinner-border spinner-border-sm" aria-hidden="true"></span>正在打开页面…';
      document.body.appendChild(bar);
    }, 500);
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
