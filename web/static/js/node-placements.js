// 节点部署位增删：只在表单中暂存，点“保存”后随节点一起提交。
import { Modal } from '/static/tabler/js/tabler.esm.min.js';

window.AirOpsPage.register(() => {
const pane = document.querySelector('#placements-pane');
const dialog = document.querySelector('#placement-delete');
if (pane && dialog) {
    const modal = Modal.getOrCreateInstance(dialog);
    const target = dialog.querySelector('#placement-delete-target');
    const error = dialog.querySelector('[data-modal-error]');
    let current = null;

    const cards = () => Array.from(pane.querySelectorAll('[data-placement]'));
    const staged = (card) => !card.querySelector('[data-del-id]').disabled;

    function mark(card, targetID) {
        const on = targetID !== null;
        card.querySelector('[data-del-id]').disabled = !on;
        const t = card.querySelector('[data-del-target]');
        t.disabled = !on;
        t.value = on ? targetID : '';
        const badge = card.querySelector('[data-del-badge]');
        const to = on && targetID ? cards().find((c) => c.dataset.placement === targetID) : null;
        badge.textContent = to ? '待删除，转移到 ' + to.dataset.name : '待删除';
        badge.hidden = !on;
        card.querySelector('[data-del-undo]').hidden = !on;
        card.querySelector('[data-placement-remove]').hidden = on;
        card.classList.toggle('opacity-50', on);
    }

    function addItem(option, auto) {
        if (pane.querySelector('[data-add-item="' + option.value + '"]')) { return; }
        const item = document.createElement('div');
        item.className = 'border border-primary rounded p-2 mb-2 d-flex align-items-center gap-2';
        item.dataset.addItem = option.value;
        if (auto) { item.dataset.auto = ''; }
        const input = Object.assign(document.createElement('input'), { type: 'hidden', name: 'add_server', value: option.value });
        const badge = Object.assign(document.createElement('span'), { className: 'badge bg-blue-lt', textContent: '待添加' });
        const label = Object.assign(document.createElement('span'), { textContent: option.textContent });
        const cancel = Object.assign(document.createElement('button'), { type: 'button', className: 'btn-close ms-auto' });
        cancel.setAttribute('aria-label', '取消添加');
        cancel.dataset.addCancel = '';
        item.append(input, badge, label, cancel);
        pane.querySelector('[data-add-list]').append(item);
    }

    // 新建：地区或类型变化时重新自动匹配（规则同服务端 serverMatches），手动添加的保留
    const form = pane.closest('form');
    if (form.dataset.nodeId === '0') {
        const region = form.querySelector('#region');
        const type = form.querySelector('#type');
        const rematch = () => {
            pane.querySelectorAll('[data-add-item][data-auto]').forEach((x) => x.remove());
            const r = region.value.trim();
            if (!r) { return; }
            pane.querySelectorAll('#padd_server option[value]:not([value=""])').forEach((o) => {
                if (o.dataset.region.trim() === r && (type.value === '1' || 'proxyOk' in o.dataset)) { addItem(o, true); }
            });
        };
        region.addEventListener('change', rematch);
        type.addEventListener('change', rematch);
    }

    pane.addEventListener('click', (e) => {
        const remove = e.target.closest('[data-placement-remove]');
        if (remove) {
            current = remove.closest('[data-placement]');
            const domains = current.querySelector('[data-domains]').innerHTML.trim();
            dialog.querySelector('[data-modal-name]').textContent = current.dataset.name;
            dialog.querySelector('[data-modal-domains]').innerHTML = domains;
            dialog.querySelector('[data-modal-bound]').hidden = !domains;
            dialog.querySelector('[data-modal-free]').hidden = !!domains;
            target.innerHTML = '<option value="">请选择</option>';
            // 目标：部署过、未暂存删除的其他部署位
            cards().filter((c) => c !== current && 'running' in c.dataset && c.querySelector('[data-del-id]') && !staged(c)).forEach((c) => {
                target.add(new Option(c.dataset.name, c.dataset.placement));
            });
            error.textContent = domains && target.options.length === 1 ? '没有可承接的部署过的部署位，请先部署其他部署位' : '';
            modal.show();
            return;
        }
        const undo = e.target.closest('[data-del-undo]');
        if (undo) {
            mark(undo.closest('[data-placement]'), null);
            return;
        }
        if (e.target.closest('[data-add-placement]')) {
            const select = pane.querySelector('#padd_server');
            if (select.value) { addItem(select.selectedOptions[0], false); }
            select.value = '';
            return;
        }
        const cancel = e.target.closest('[data-add-cancel]');
        if (cancel) { cancel.closest('[data-add-item]').remove(); }
    });

    dialog.querySelector('[data-modal-confirm]').addEventListener('click', () => {
        const bound = !dialog.querySelector('[data-modal-bound]').hidden;
        if (bound && !target.value) {
            error.textContent = '请选择转移目标';
            target.focus();
            return;
        }
        if (!cards().some((c) => c !== current && c.querySelector('[data-del-id]') && !staged(c))) {
            error.textContent = '这是节点最后一个部署位，不能删除；如需下线请删除节点';
            return;
        }
        mark(current, bound ? target.value : '');
        modal.hide();
    });

    // 校验失败回显：恢复暂存的删除
    cards().forEach((c) => {
        if ('stagedTarget' in c.dataset && c.querySelector('[data-del-id]')) {
            mark(c, c.dataset.stagedTarget === '0' ? '' : c.dataset.stagedTarget);
        }
    });
}
});
