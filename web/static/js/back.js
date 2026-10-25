// 列表页记住当前页（含分页/筛选），编辑页的“取消”和保存后的跳转回到该页。
window.AirOpsPage.register(function () {
  var m = location.pathname.match(/^\/admin\/[a-z]+/);
  if (!m) { return; }
  var base = m[0], key = 'back:' + base;
  if (location.pathname === base) { sessionStorage.setItem(key, base + location.search); return; }
  var back = sessionStorage.getItem(key);
  if (!back) { return; }
  document.querySelectorAll('.card-footer a.btn-link[href="' + base + '"], a[data-back][href="' + base + '"]').forEach(function (a) { a.href = back; });
  document.querySelectorAll('form[method=post]').forEach(function (f) {
    var path = new URL(f.action, location.href).pathname;
    if (path !== base && path.indexOf(base + '/') !== 0) { return; }
    var i = document.createElement('input');
    i.type = 'hidden'; i.name = 'back'; i.value = back; f.appendChild(i);
  });
});
