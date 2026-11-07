// 部署任务页有排队或进行中的任务时每 5 秒刷新；局部导航离开页面即停止。
window.AirOpsPage.register(() => {
  if (!document.querySelector('[data-deploy-refresh]')) return;
  const timer = setTimeout(() => location.reload(), 5000);
  return () => clearTimeout(timer);
});
