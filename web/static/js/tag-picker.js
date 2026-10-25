// 标签在当前表单内选择和维护；新增自动选中，编辑保持原选择，不丢弃其他未保存字段。
(function () {
    document.querySelectorAll('[data-tag-picker]').forEach(function (root) {
        var list = root.querySelector('[data-tag-list]');
        var editor = root.querySelector('[data-tag-editor]');
        var name = root.querySelector('[data-tag-name-input]');
        var color = root.querySelector('[data-tag-color-input]');
        var save = root.querySelector('[data-tag-save]');
        var error = root.querySelector('[data-tag-error]');
        var current = null;
        var busy = false;
        function open(pill) {
            if (busy) { return; }
            current = pill;
            name.value = pill ? pill.dataset.tagName : '';
            color.value = pill && /^#[0-9a-f]{6}$/i.test(pill.dataset.tagColor) ? pill.dataset.tagColor : '#206bc4';
            root.querySelector('[data-tag-title]').textContent = pill ? '编辑标签' : '新增标签';
            error.textContent = '';
            editor.hidden = false;
            name.focus();
        }
        root.querySelector('[data-tag-new]').addEventListener('click', function () { open(null); });
        root.querySelector('[data-tag-cancel]').addEventListener('click', function () { if (!busy) { editor.hidden = true; } });
        list.addEventListener('click', function (e) {
            var edit = e.target.closest('.tag-edit');
            if (edit) { open(edit.closest('[data-tag-id]')); }
        });
        name.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') { e.preventDefault(); save.click(); }
        });
        save.addEventListener('click', async function () {
            if (busy) { return; }
            var label = name.value.trim();
            if (!label) { error.textContent = '请填写标签名称'; name.focus(); return; }
            var pill = current;
            var form = root.closest('form');
            var params = new URLSearchParams({csrf: form.elements.csrf.value, name: label, color: color.value});
            if (pill) { params.set('id', pill.dataset.tagId); }
            busy = true;
            save.disabled = true;
            error.textContent = '';
            try {
                var response = await fetch('/admin/tag-picker', {method: 'POST', body: params});
                if (!response.ok) { throw new Error(await response.text()); }
                var tag = await response.json();
                if (!pill) {
                    pill = document.createElement('span');
                    pill.className = 'tag-pill d-inline-flex align-items-center rounded-pill border ps-2 pe-1 py-1 small';
                    pill.innerHTML = '<label class="d-inline-flex align-items-center m-0 cursor-pointer"><input type="checkbox" class="visually-hidden" name="tags"><span class="status-dot flex-shrink-0 me-1"></span><span data-tag-label></span><i class="ti ti-check tag-check ms-1" aria-hidden="true"></i></label><button type="button" class="tag-edit border-0 bg-transparent text-secondary ms-1 px-1"><i class="ti ti-pencil" aria-hidden="true"></i></button>';
                    pill.querySelector('input').value = tag.id;
                    pill.querySelector('input').checked = true;
                    list.insertBefore(pill, root.querySelector('[data-tag-new]'));
                }
                pill.dataset.tagId = tag.id;
                pill.dataset.tagName = tag.name;
                pill.dataset.tagColor = tag.color;
                pill.querySelector('[data-tag-label]').textContent = tag.name;
                pill.querySelector('.status-dot').style.backgroundColor = tag.color;
                pill.querySelector('.tag-edit').setAttribute('aria-label', '编辑标签 ' + tag.name);
                editor.hidden = true;
            } catch (e) {
                error.textContent = e.message || '保存失败，请重试';
            } finally {
                busy = false;
                save.disabled = false;
            }
        });
    });
})();
