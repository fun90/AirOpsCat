import { Modal } from '/static/tabler/js/tabler.esm.min.js';

// 初始化（01～10 全部执行）或执行了 @reboot-prompt 脚本且全部成功后弹窗确认重启，结果追加到执行结果区。
function confirmReboot(targets, csrf, result) {
    const dialog = document.getElementById('maintenance-reboot');
    if (!dialog || !targets.length) return;
    const modal = Modal.getOrCreateInstance(dialog);
    const ok = dialog.querySelector('[data-maintenance-reboot-ok]');
    const list = dialog.querySelector('[data-maintenance-reboot-list]');
    list.replaceChildren(...targets.map(t => { const div = document.createElement('div'); div.textContent = t.label; return div; }));
    ok.onclick = async () => {
        ok.classList.add('btn-loading');
        ok.disabled = true;
        const lines = await Promise.all(targets.map(async target => {
            try {
                const response = await fetch(`/admin/servers/${encodeURIComponent(target.id)}/reboot`, {
                    method: 'POST', redirect: 'error', headers: { Accept: 'application/json' },
                    body: new URLSearchParams({ csrf })
                });
                if (response.ok) return `${target.label}：已发送重启命令，约 1 分钟后恢复。`;
                return `${target.label}：${(await response.text()).trim() || '重启失败'}`;
            } catch {
                return `${target.label}：重启请求失败，请检查系统日志。`;
            }
        }));
        lines.forEach(line => { const p = document.createElement('div'); p.textContent = line; result.append(p); });
        ok.classList.remove('btn-loading');
        ok.disabled = false;
        modal.hide();
    };
    modal.show();
}

window.AirOpsPage.register(() => {
const form = document.querySelector('[data-maintenance-batch]');
if (form) {
    const scripts = [...form.querySelectorAll('[name="scripts"]')];
    const result = form.querySelector('[data-maintenance-result]');
    const keyBox = form.querySelector('[data-maintenance-key]');
    const publicKey = form.elements.public_key;
    const emailBox = form.querySelector('[data-maintenance-email]');
    const acmeEmail = form.elements.acme_email;
    const status = (input, text, color = 'secondary') => {
        const badge = input.closest('[data-maintenance-row]').querySelector('[data-maintenance-status]');
        badge.textContent = text;
        badge.className = `badge bg-${color}-lt flex-shrink-0`;
    };
    const update = () => {
        const selected = scripts.filter(input => input.checked);
        form.querySelector('[data-maintenance-count]').textContent = `已选 ${selected.length} 项`;
        const needsKey = selected.some(input => input.value === '03-ssh-key.sh');
        keyBox.hidden = !needsKey;
        publicKey.required = needsKey;
        const needsEmail = selected.some(input => input.value === '04-certificates-acme.sh');
        emailBox.hidden = !needsEmail;
        acmeEmail.required = needsEmail;
        scripts.forEach(input => status(input, input.checked ? '待执行' : '未选择'));
    };
    scripts.forEach(input => input.addEventListener('change', update));
    form.querySelectorAll('[data-maintenance-select]').forEach(button => {
        button.addEventListener('click', () => {
            scripts.forEach(input => {
                input.checked = button.dataset.maintenanceSelect === 'all' ||
                    (button.dataset.maintenanceSelect === 'init' && Number(input.value.slice(0, 2)) <= 10);
            });
            update();
        });
    });
    if ('maintenanceInit' in form.dataset) form.querySelector('[data-maintenance-select="init"]').click();
    let running = false;
    form.addEventListener('submit', async event => {
        event.preventDefault();
        if (running || !form.reportValidity()) return;
        const selected = scripts.filter(input => input.checked).sort((a, b) => a.value.localeCompare(b.value));
        if (!selected.length) { result.textContent = '请至少选择一个脚本。'; return; }
        const server = form.elements.server_id;
        const targets = [...server.selectedOptions].map(option => ({ id: option.value, label: option.textContent }));
        if (!confirm(`将在 ${targets.length} 台服务器上各按顺序执行 ${selected.length} 个脚本，确定？\n${targets.map(t => t.label).join('\n')}`)) return;
        running = true;
        document.body.dataset.navigationNative = '';
        const common = { csrf: form.elements.csrf.value, public_key: publicKey.value, acme_email: acmeEmail.value, confirm: 'run' };
        const controls = [...form.querySelectorAll('input,select,textarea,button')];
        const states = controls.map(control => control.disabled);
        controls.forEach(control => { control.disabled = true; });
        server.tomselect?.disable();
        form.querySelector('[data-maintenance-run]').classList.add('btn-loading');
        const beforeUnload = event => { event.preventDefault(); event.returnValue = ''; };
        window.addEventListener('beforeunload', beforeUnload);
        const summary = [];
        const succeeded = [];
        const initScripts = scripts.filter(input => Number(input.value.slice(0, 2)) <= 10);
        const promptReboot = (initScripts.length > 0 && initScripts.every(input => input.checked)) ||
            selected.some(input => 'rebootPrompt' in input.dataset);
        try {
            for (const target of targets) {
            const label = target.label;
            let completed = 0;
            scripts.forEach(input => status(input, input.checked ? '待执行' : '未选择'));
            for (const input of selected) {
                status(input, '执行中', 'blue');
                result.textContent = `${label}：正在执行 ${input.value}（${completed + 1}/${selected.length}）`;
                try {
                    const response = await fetch('/admin/maintenance/run', {
                        method: 'POST', redirect: 'error', headers: { Accept: 'application/json' },
                        body: new URLSearchParams({ ...common, server_id: target.id, script: input.value, hash: input.dataset.hash })
                    });
                    if (!response.ok || !response.headers.get('Content-Type')?.includes('application/json')) {
                        // 只采用后端 http.Error 的纯文本原因，代理返回的 HTML 错误页仍用通用提示
                        const plain = !response.ok && response.headers.get('Content-Type')?.startsWith('text/plain');
                        const reason = plain ? (await response.text().catch(() => '')).trim().slice(0, 300) : '';
                        throw new Error(reason ? `${reason}。` : '执行失败或连接中断，请检查系统日志和服务器实际状态。');
                    }
                    const data = await response.json();
                    if (!data.success) throw new Error('执行未成功，请检查系统日志。');
                } catch (error) {
                    status(input, '失败 / 待核实', 'red');
                    selected.slice(completed + 1).forEach(item => status(item, '已停止', 'secondary'));
                    summary.push(`${label}：${input.value} ${error.message} 后续步骤已停止，已完成 ${completed} 项。`);
                    completed = -1;
                    break;
                }
                completed++;
                status(input, '成功', 'green');
            }
            if (completed >= 0) { summary.push(`${label}：全部 ${completed} 项执行成功。`); succeeded.push(target); }
            }
            result.innerHTML = '';
            summary.forEach(line => { const p = document.createElement('div'); p.textContent = line; result.append(p); });
        } finally {
            running = false;
            delete document.body.dataset.navigationNative;
            controls.forEach((control, index) => { control.disabled = states[index]; });
            server.tomselect?.enable();
            form.querySelector('[data-maintenance-run]').classList.remove('btn-loading');
            window.removeEventListener('beforeunload', beforeUnload);
        }
        if (promptReboot) confirmReboot(succeeded, common.csrf, result);
    });
}
});
