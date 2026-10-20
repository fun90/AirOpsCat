// 在样式加载前应用系统主题，并跟随系统设置实时切换。
(function () {
  var preference = window.matchMedia('(prefers-color-scheme: dark)');
  function apply() {
    document.documentElement.setAttribute('data-bs-theme', preference.matches ? 'dark' : 'light');
  }
  apply();
  preference.addEventListener('change', apply);
})();
