// 记住各列表页的分页与筛选。返回和表单取消优先回到同源管理端来源页；无来源时回退到最近访问的对应列表页。
window.AirOpsPage.register(function () {
  var m = location.pathname.match(/^\/admin\/[^/]+/);
  if (!m) { return; }
  var base = m[0], key = 'back:' + base;
  if (location.pathname === base) {
    sessionStorage.setItem(key, base + location.search);
    return;
  }
  var back = '';
  try {
    var ref = new URL(document.referrer);
    if (ref.origin === location.origin && ref.pathname.indexOf('/admin/') === 0 && ref.href !== location.href) {
      ref.searchParams.delete('msg'); ref.searchParams.delete('err');
      back = ref.pathname + ref.search + ref.hash;
    }
  } catch (_) {}
  if (!back) { back = sessionStorage.getItem(key) || ''; }
  if (!back) { return; }
  var listPage = /^\/admin\/[^/?#]+(\?|#|$)/;
  document.querySelectorAll('a[data-back], .card-footer a.btn-link[href]').forEach(function (a) {
    if (a.hasAttribute('data-back') || listPage.test(a.getAttribute('href'))) { a.href = back; }
  });
  document.querySelectorAll('form[method=post]').forEach(function (f) {
    var path = new URL(f.action, location.href).pathname;
    if (path !== base && path.indexOf(base + '/') !== 0) { return; }
    var i = document.createElement('input');
    i.type = 'hidden'; i.name = 'back'; i.value = back; f.appendChild(i);
  });
});
