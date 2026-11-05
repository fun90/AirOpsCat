import { Modal } from '/static/tabler/js/tabler.esm.min.js';

// 带 data-confirm="提示文字" 的表单提交前弹出确认模态框；data-confirm-danger 时确认按钮为红色。
// 捕获阶段拦截，确认后用 requestSubmit 重新提交，loading.js 仍按正常提交处理。
let dialog, modal, pending;
function build() {
    dialog = document.createElement('div');
    dialog.className = 'modal modal-blur fade';
    dialog.tabIndex = -1;
    dialog.innerHTML = '<div class="modal-dialog modal-sm modal-dialog-centered"><div class="modal-content"><div class="modal-body text-break" data-confirm-text></div><div class="modal-footer"><button type="button" class="btn me-auto" data-bs-dismiss="modal">取消</button><button type="button" class="btn" data-confirm-ok>确定</button></div></div></div>';
    document.body.appendChild(dialog);
    modal = new Modal(dialog);
    dialog.querySelector('[data-confirm-ok]').addEventListener('click', () => {
        const form = pending;
        pending = null;
        modal.hide();
        if (form?.isConnected) { form.dataset.confirmed = ''; form.requestSubmit(); }
    });
    dialog.addEventListener('hidden.bs.modal', () => { pending = null; });
}
document.addEventListener('submit', event => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement) || !form.dataset.confirm) { return; }
    if ('confirmed' in form.dataset) { delete form.dataset.confirmed; return; }
    event.preventDefault();
    if (!dialog?.isConnected) { build(); }
    pending = form;
    let text = form.dataset.confirm;
    if (form.hasAttribute('data-batch-form')) {
        const names = [...document.querySelectorAll('[data-node-select]:checked')].map(input => input.dataset.name);
        if (names.length) text += '\n\n' + names.slice(0, 8).join('、') + (names.length > 8 ? ` 等共 ${names.length} 个` : '');
    }
    dialog.querySelector('[data-confirm-text]').textContent = text;
    dialog.querySelector('[data-confirm-ok]').className = 'btn ' + ('confirmDanger' in form.dataset ? 'btn-danger' : 'btn-primary');
    modal.show();
}, true);
