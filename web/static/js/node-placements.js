// 节点部署位增删：只在表单中暂存，点“保存”后随节点一起提交。
import { Dropdown, Modal } from '/static/tabler/js/tabler.esm.min.js';

window.AirOpsPage.register(() => {
const pane = document.querySelector('#placements-pane');
const dialog = document.querySelector('#placement-delete');
if (pane && dialog) {
    const modal = Modal.getOrCreateInstance(dialog);
    const target = dialog.querySelector('#placement-delete-target');
    const error = dialog.querySelector('[data-modal-error]');
    let current = null;

    const cards = () => Array.from(pane.querySelectorAll('[data-placement]'));
    const hostTpl = pane.querySelector('[data-new-host-tpl]');

    // 新增访问域名：已有卡片和待添加项共用模板，字段名按服务器 ID 区分
    function openHost(holder, name, domain) {
        if (holder.querySelector('[data-new-host]')) { return; }
        const box = hostTpl.content.firstElementChild.cloneNode(true);
        const k = 'p' + holder.dataset.server + '_';
        const input = box.querySelector('[data-new-name]');
        const select = box.querySelector('[data-new-domain]');
        input.name = k + 'new_name';
        select.name = k + 'new_domain_id';
        input.value = name || '';
        if (domain) { select.value = domain; }
        holder.append(box);
        holder.querySelector('[data-new-host-toggle]').hidden = true;
        return input;
    }
    function closeHost(holder) {
        holder.querySelector('[data-new-host]')?.remove();
        const toggle = holder.querySelector('[data-new-host-toggle]');
        if (toggle) { toggle.hidden = false; }
    }

    // 已有部署位的访问域名用 Tom Select：输入完整域名即可新增，按已知主域名拆成 new_name / new_domain_id 提交
    const domains = Array.from(hostTpl.content.querySelectorAll('[data-new-domain] option'), (o) => ({ id: o.value, name: o.textContent.trim().toLowerCase() }));
    function splitHost(input) {
        const s = input.trim().toLowerCase().replace(/\.$/, '');
        const d = domains.filter((x) => s.endsWith('.' + x.name)).sort((a, b) => b.name.length - a.name.length)[0];
        return d && { name: s.slice(0, -d.name.length - 1), domain: d.id, full: s };
    }
    function syncNewHost(card) {
        card.querySelectorAll('[data-new-host-field]').forEach((i) => i.remove());
        const ts = card.querySelector('select[name$="host"]')?.tomselect;
        const h = ts && ts.getValue() === 'new' && splitHost(ts.options.new.text);
        if (!h || staged(card)) { return; }
        const k = 'p' + card.dataset.server + '_';
        card.append(...[[k + 'new_name', h.name], [k + 'new_domain_id', h.domain]].map(([name, value]) => {
            const i = Object.assign(document.createElement('input'), { type: 'hidden', name, value });
            i.dataset.newHostField = '';
            return i;
        }));
    }
    function hostSelect(card) {
        const sel = card.querySelector('select[name$="host"]');
        if (!sel || sel.tomselect || !window.TomSelect) { return; }
        const example = 'hk01.' + (domains[0]?.name || 'example.com');
        const ts = new TomSelect(sel, {
            maxOptions: null, allowEmptyOption: true,
            createFilter: (input) => !!splitHost(input),
            create: (input) => {
                const h = splitHost(input);
                if (!h) { return false; }
                if (ts.options.new) { ts.removeOption('new'); }
                return { value: 'new', text: h.full };
            },
            render: {
                option_create: (data, escape) => '<div class="create">新增 <strong>' + escape(data.input) + '</strong></div>',
                no_results: (data) => splitHost(data.input) ? null : '<div class="no-results">无匹配项，输入完整域名可新增，如 ' + example + '</div>',
            },
            onChange: () => syncNewHost(card),
            // 输入时隐藏当前项，避免与输入框挤成两行
            onType: (str) => ts.wrapper.classList.toggle('ts-typing', !!str),
            onDropdownClose: () => ts.wrapper.classList.remove('ts-typing'),
        });
    }

    function staged(card) { const d = card.querySelector('[data-del-id]'); return !!d && !d.disabled; }

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
        syncNewHost(card);
    }

    function addItem(option, auto) {
        if (pane.querySelector('[data-add-item="' + option.value + '"]')) { return; }
        const item = document.createElement('div');
        item.className = 'border border-primary rounded p-2 mb-2 d-flex flex-wrap align-items-center gap-2';
        item.dataset.addItem = option.value;
        item.dataset.server = option.value;
        if (auto) { item.dataset.auto = ''; }
        const input = Object.assign(document.createElement('input'), { type: 'hidden', name: 'add_server', value: option.value });
        const badge = Object.assign(document.createElement('span'), { className: 'badge bg-blue-lt', textContent: '待添加' });
        const label = Object.assign(document.createElement('span'), { textContent: option.textContent });
        const toggle = Object.assign(document.createElement('a'), { href: '#', className: 'small ms-auto', textContent: '新增域名' });
        toggle.dataset.newHostToggle = '';
        const cancel = Object.assign(document.createElement('button'), { type: 'button', className: 'btn-close' });
        cancel.setAttribute('aria-label', '取消添加');
        cancel.dataset.addCancel = '';
        item.append(input, badge, label, toggle, cancel);
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

    // 便宜时段：两个时间框收在下拉弹层里，按钮文字随输入同步
    function cheapLabel(box) {
        const [start, end] = box.querySelectorAll('input[type="time"]');
        const set = start.value || end.value;
        box.querySelector('[data-cheap-label]').textContent = set ? start.value + '–' + end.value : '便宜时段';
        box.querySelector('[data-bs-toggle]').classList.toggle('text-secondary', !set);
    }
    pane.addEventListener('input', (e) => {
        const box = e.target.closest('[data-cheap]');
        if (box) { cheapLabel(box); }
    });

    pane.addEventListener('click', (e) => {
        const cheap = e.target.closest('[data-cheap-clear], [data-cheap-done]');
        if (cheap) {
            const box = cheap.closest('[data-cheap]');
            if ('cheapClear' in cheap.dataset) { box.querySelectorAll('input[type="time"]').forEach((i) => { i.value = ''; }); }
            cheapLabel(box);
            Dropdown.getOrCreateInstance(box.querySelector('[data-bs-toggle]')).hide();
            return;
        }
        const toggle = e.target.closest('[data-new-host-toggle]');
        if (toggle) {
            e.preventDefault();
            openHost(toggle.closest('[data-server]'))?.focus();
            return;
        }
        const hostCancel = e.target.closest('[data-new-host-cancel]');
        if (hostCancel) {
            closeHost(hostCancel.closest('[data-server]'));
            return;
        }
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

    // 校验失败回显：恢复暂存的新增域名和删除
    cards().forEach(hostSelect);
    pane.querySelectorAll('[data-server][data-new-name]').forEach((h) => {
        const ts = h.querySelector('select[name$="host"]')?.tomselect;
        if (!ts) { openHost(h, h.dataset.newName, h.dataset.newDomain); return; }
        const d = domains.find((x) => x.id === h.dataset.newDomain);
        if (!d) { return; }
        ts.addOption({ value: 'new', text: h.dataset.newName + '.' + d.name });
        ts.setValue('new');
    });
    cards().forEach((c) => {
        if ('stagedTarget' in c.dataset && c.querySelector('[data-del-id]')) {
            mark(c, c.dataset.stagedTarget === '0' ? '' : c.dataset.stagedTarget);
        }
    });

    // 经服务端配置的国内探测服务器 SSH 拨测部署位端口
    const csrf = document.querySelector('input[name="csrf"]')?.value || '';
    pane.querySelectorAll('[data-tcp-probe]').forEach((btn) => btn.addEventListener('click', async () => {
        const out = btn.nextElementSibling;
        btn.disabled = true;
        out.className = 'small text-secondary';
        out.textContent = '检测中…';
        try {
            const res = await fetch(`/admin/placements/${btn.dataset.tcpProbe}/probe`, {
                method: 'POST', redirect: 'error', headers: { Accept: 'application/json' }, body: new URLSearchParams({ csrf })
            });
            if (!res.ok) { throw new Error((await res.text()).trim() || `HTTP ${res.status}`); }
            out.className = 'small';
            out.replaceChildren(...(await res.json()).flatMap((p, i) => {
                const span = document.createElement('span');
                span.className = p.ok ? 'text-success' : 'text-danger';
                span.textContent = `${p.name} ${p.msg}${p.ok ? ` ${p.ms} ms` : ''}`;
                return i ? [' · ', span] : [span];
            }));
        } catch (e) {
            out.className = 'small text-danger';
            out.textContent = `检测失败：${e.message}`;
        }
        btn.disabled = false;
    }));
}
});
