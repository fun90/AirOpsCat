// 原生提交的表单：提交按钮显示加载动画并拦截重复提交；脚本或确认框已取消的提交不处理。
document.addEventListener('submit', function (event) {
  var form = event.target;
  if (event.defaultPrevented || form.target) { return; }
  if ('submitting' in form.dataset) { event.preventDefault(); return; }
  form.dataset.submitting = '';
  // 不能禁用按钮：禁用的提交者不会把 name/value 带进表单数据（如“保存并部署”）。
  var button = event.submitter || form.querySelector('[type=submit], button:not([type])');
  if (button && button.classList.contains('btn')) { button.classList.add('btn-loading'); }
});
// 从浏览器往返缓存恢复的页面要解除提交状态。
window.addEventListener('pageshow', function (event) {
  if (!event.persisted) { return; }
  document.querySelectorAll('form[data-submitting]').forEach(function (f) { delete f.dataset.submitting; });
  document.querySelectorAll('.btn-loading').forEach(function (b) { b.classList.remove('btn-loading'); });
});
