// 协议变化或点「重新生成」时初始化入站；替换非系统生成的配置前先确认，加载失败保留原配置。
window.AirOpsPage.register(function () {
    var protocol = document.querySelector('#protocol');
    var type = document.querySelector('#type');
    var ta = document.querySelector('#inbound');
    var regenerate = document.querySelector('[data-regenerate-inbound]');
    if (!protocol || !type || !ta || !regenerate) { return; }
    var previous = protocol.value;
    var previousType = type.value;
    var serial = 0;
    // 新增页的初始配置由系统生成，切换协议时无需确认。
    var generated = ta.form.dataset.nodeId === '0' ? ta.value : null;
    function options() {
        Array.from(protocol.options).forEach(function (o) {
            var landing = o.value === 'socks' || o.value === 'shadowsocks';
            o.disabled = landing !== (type.value === '1');
        });
    }
    async function initialize(force) {
        var selected = protocol.value;
        if (selected === previous && !force) { return; }
        if (ta.value.trim() && ta.value.trim() !== '{}' && ta.value !== generated && !window.confirm((force ? '重新生成' : '切换协议') + '会替换当前入站配置，是否继续？')) {
            protocol.value = previous;
            type.value = previousType;
            options();
            return;
        }
        var original = ta.value;
        var request = ++serial;
        protocol.disabled = true;
        type.disabled = true;
        regenerate.disabled = true;
        var submit = ta.form.querySelector('[type="submit"]');
        submit.disabled = true;
        try {
            var query = new URLSearchParams({protocol: selected, node_id: ta.form.dataset.nodeId, region: ta.form.elements.region.value, type: type.value});
            var response = await fetch('/admin/nodes/default-inbound?' + query, {cache: 'no-store'});
            if (!response.ok) { throw new Error(response.status === 400 ? await response.text() : '初始化入站配置失败'); }
            var text = await response.text();
            JSON.parse(text);
            if (request !== serial) { return; }
            if (ta.value !== original) { throw new Error('加载期间配置已被编辑'); }
            if (query.get('region') !== ta.form.elements.region.value || query.get('type') !== type.value || query.get('protocol') !== protocol.value) { throw new Error('加载期间节点地区或协议已变化'); }
            ta.value = text;
            generated = text;
            ta.dispatchEvent(new Event('input', {bubbles: true}));
            previous = selected;
            previousType = type.value;
        } catch (e) {
            if (request !== serial) { return; }
            protocol.value = previous;
            type.value = previousType;
            options();
            window.alert(e.message + '；已保留原配置，请检查后重试');
        } finally {
            protocol.disabled = false;
            type.disabled = false;
            regenerate.disabled = false;
            submit.disabled = false;
        }
    }
    protocol.addEventListener('change', function () { initialize(false); });
    regenerate.addEventListener('click', function () { initialize(true); });
    type.addEventListener('change', function () {
        options();
        if (protocol.selectedOptions[0].disabled) {
            protocol.value = Array.from(protocol.options).find(function (o) { return !o.disabled; }).value;
        }
        initialize(false);
    });
    // 浏览器验证隐藏 Tab 中的字段失败时，先打开对应页签再聚焦。
    ta.form.addEventListener('invalid', function (e) {
        var pane = e.target.closest('.tab-pane');
        if (pane && !pane.classList.contains('active')) {
            var trigger = document.querySelector('[data-bs-target="#' + pane.id + '"]');
            if (trigger) { trigger.click(); }
        }
    }, true);
    options();
    return function () { ++serial; };
});
