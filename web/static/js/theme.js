// 在样式加载前应用主题：优先使用手动选择的主题，未设置时跟随系统并实时切换，并同步 iOS/浏览器 theme-color。
(function () {
  var key = 'airopscat-theme';
  var preference = window.matchMedia('(prefers-color-scheme: dark)');
  function saved() {
    try {
      var v = localStorage.getItem(key);
      return v === 'dark' || v === 'light' ? v : '';
    } catch (_) { return ''; }
  }
  function current() {
    return saved() || (preference.matches ? 'dark' : 'light');
  }
  function syncThemeColor(theme) {
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) { meta.setAttribute('content', theme === 'dark' ? '#09090b' : '#ffffff'); }
  }
  function apply(theme) {
    document.documentElement.setAttribute('data-bs-theme', theme);
    syncThemeColor(theme);
  }
  apply(current());
  document.addEventListener('DOMContentLoaded', function () {
    syncThemeColor(document.documentElement.getAttribute('data-bs-theme') || current());
  });
  preference.addEventListener('change', function () {
    if (!saved()) { apply(preference.matches ? 'dark' : 'light'); }
  });
  document.addEventListener('click', function (event) {
    var btn = event.target.closest('[data-theme-toggle]');
    if (!btn) { return; }
    var next = document.documentElement.getAttribute('data-bs-theme') === 'dark' ? 'light' : 'dark';
    try { localStorage.setItem(key, next); } catch (_) {}
    apply(next);
  });
})();
