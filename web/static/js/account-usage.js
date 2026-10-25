import { Popover } from '/static/tabler/js/tabler.esm.min.js';

let active = null;
const close = () => {
    if (!active) return;
    active.popover.dispose();
    active.button.setAttribute('aria-expanded', 'false');
    active = null;
};
window.AirOpsPage.register(() => {
for (const button of document.querySelectorAll('[data-account-usage]')) {
    button.addEventListener('click', async () => {
        if (active?.button === button) { close(); return; }
        close();
        const popover = new Popover(button, {
            title: '当前周期流量', content: '正在读取…', trigger: 'manual',
            placement: 'auto', container: 'body', html: false, animation: false
        });
        active = { button, popover };
        button.setAttribute('aria-expanded', 'true');
        popover.show();
        try {
            const response = await fetch(`/admin/accounts/${button.dataset.accountUsage}/usage`, {
                headers: { Accept: 'application/json' }, cache: 'no-store'
            });
            if (!response.ok) throw new Error('读取失败');
            const data = await response.json();
            if (active?.popover !== popover) return;
            document.getElementById(button.getAttribute('aria-describedby'))?.querySelector('.popover-body')?.replaceChildren(document.createTextNode(`已用 ${data.used} · ${data.percent}\n周期：${data.start} 至 ${data.end}`));
            popover.update();
        } catch (_) {
            if (active?.popover === popover) {
                document.getElementById(button.getAttribute('aria-describedby'))?.querySelector('.popover-body')?.replaceChildren(document.createTextNode('读取失败，请关闭后重试。'));
                popover.update();
            }
        }
    });
}
return close;
});
document.addEventListener('click', event => {
    if (!event.target.closest('[data-account-usage], .popover')) close();
});
document.addEventListener('keydown', event => { if (event.key === 'Escape') close(); });
