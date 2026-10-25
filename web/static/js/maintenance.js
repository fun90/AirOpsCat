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
        const common = { csrf: form.elements.csrf.value, public_key: publicKey.value, acme_email: acmeEmail.value, confirm: 'run' };
        const controls = [...form.querySelectorAll('input,select,textarea,button')];
        const states = controls.map(control => control.disabled);
        controls.forEach(control => { control.disabled = true; });
        server.tomselect?.disable();
        const beforeUnload = event => { event.preventDefault(); event.returnValue = ''; };
        window.addEventListener('beforeunload', beforeUnload);
        const summary = [];
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
                        throw new Error('执行失败或连接中断，请检查系统日志和服务器实际状态。');
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
            if (completed >= 0) summary.push(`${label}：全部 ${completed} 项执行成功。`);
            }
            result.innerHTML = '';
            summary.forEach(line => { const p = document.createElement('div'); p.textContent = line; result.append(p); });
        } finally {
            running = false;
            controls.forEach((control, index) => { control.disabled = states[index]; });
            server.tomselect?.enable();
            window.removeEventListener('beforeunload', beforeUnload);
        }
    });
}
