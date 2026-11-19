window.AirOpsPage.register(() => {
  const server = document.querySelector('#server_id[data-dns-server-ip]');
  const content = document.querySelector('#content');
  if (!server || !content) return;

  const updateContent = () => {
    const ip = server.selectedOptions[0]?.dataset.ip;
    if (!ip) return;
    content.value = ip;
    content.dispatchEvent(new Event('input', { bubbles: true }));
  };
  server.addEventListener('change', updateContent);
  return () => server.removeEventListener('change', updateContent);
});
