// coss-picker.js — coss.com/ui 风格日期选择器、日期范围选择器与 24 小时时间段选择器
// 渐进增强原生 input[type="date"]、[data-date-range] 与 [data-time-range]，保持原表单字段与外部 value 赋值兼容。
window.AirOpsPage.register(function () {
    var VALUE_DESC = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
    var WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日'];
    var active = null;
    var pop = null;

    function pad(n) { return String(n).padStart(2, '0'); }
    function nowLocal() {
        var d = new Date();
        return { y: d.getFullYear(), m: d.getMonth(), d: d.getDate() };
    }
    function parseDate(val) {
        var m = (val || '').trim().match(/^(\d{4})-(\d{2})-(\d{2})/);
        return m ? { y: +m[1], m: +m[2] - 1, d: +m[3] } : null;
    }
    function parseTime(val) {
        var m = (val || '').trim().match(/^(\d{2}):(\d{2})/);
        return m ? { h: +m[1], min: +m[2] } : null;
    }
    function fmtDate(st) { return st ? st.y + '-' + pad(st.m + 1) + '-' + pad(st.d) : ''; }
    function fmtTime(st) { return st ? pad(st.h) + ':' + pad(st.min) : ''; }
    function dateKey(st) { return st ? st.y * 10000 + (st.m + 1) * 100 + st.d : 0; }
    function addDays(st, delta) {
        var d = new Date(st.y, st.m, st.d + delta);
        return { y: d.getFullYear(), m: d.getMonth(), d: d.getDate() };
    }

    function el(tag, cls, text) {
        var node = document.createElement(tag);
        if (cls) { node.className = cls; }
        if (text != null) { node.textContent = text; }
        return node;
    }
    function btn(cls, text, onClick, label) {
        var b = el('button', cls, text);
        b.type = 'button';
        if (label) { b.setAttribute('aria-label', label); }
        if (onClick) { b.addEventListener('click', onClick); }
        return b;
    }
    function makeCalIcon() {
        var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('class', 'coss-icon coss-dt-icon');
        svg.setAttribute('aria-hidden', 'true');
        var use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
        use.setAttribute('href', '/static/icons/lucide.svg?v=3#calendar');
        svg.appendChild(use);
        return svg;
    }
    function hookValue(input, sync) {
        Object.defineProperty(input, 'value', {
            configurable: true,
            get: function () { return VALUE_DESC.get.call(this); },
            set: function (v) { VALUE_DESC.set.call(this, v); sync(); }
        });
    }
    function makeFoot(onClear, endNodes) {
        var foot = el('div', 'coss-dt-foot');
        if (onClear) { foot.appendChild(btn('btn btn-sm btn-link text-secondary p-0', '清除', onClear)); }
        var end = el('div', 'coss-dt-foot-end');
        endNodes.forEach(function (n) { end.appendChild(n); });
        foot.appendChild(end);
        return foot;
    }

    function placePop(node, anchor) {
        var rect = anchor.getBoundingClientRect();
        node.style.visibility = 'hidden';
        node.style.top = '0px';
        node.style.left = '0px';
        var fw = node.offsetWidth;
        var fh = node.offsetHeight;
        var vw = (window.visualViewport && window.visualViewport.width) || window.innerWidth;
        var vh = (window.visualViewport && window.visualViewport.height) || window.innerHeight;
        var gap = 6;
        var top = rect.bottom + gap;
        var originY = 'top';
        if (top + fh > vh - 8 && rect.top - gap - fh >= 8) {
            top = rect.top - gap - fh;
            originY = 'bottom';
        }
        top = Math.max(8, Math.min(top, vh - fh - 8));
        var left = vw < 420 ? Math.max(8, (vw - fw) / 2) : Math.max(8, Math.min(rect.left, vw - fw - 8));
        node.style.top = Math.round(top) + 'px';
        node.style.left = Math.round(left) + 'px';
        node.style.transformOrigin = originY + ' left';
        node.style.visibility = '';
    }

    function close() {
        if (active) {
            active.trigger.setAttribute('aria-expanded', 'false');
            active = null;
        }
        if (pop) {
            pop.remove();
            pop = null;
        }
    }

    function openPop(ctx, label) {
        if (active === ctx) { close(); return false; }
        close();
        active = ctx;
        ctx.trigger.setAttribute('aria-expanded', 'true');
        pop = el('div', 'coss-dt-pop');
        pop.setAttribute('role', 'dialog');
        pop.setAttribute('aria-label', label);
        pop.addEventListener('click', function (e) { e.stopPropagation(); });
        pop.addEventListener('mousedown', function (e) { e.stopPropagation(); });
        document.body.appendChild(pop);
        return true;
    }

    function buildMonthGrid(opts) {
        var cal = el('div', 'coss-dt-cal');
        var head = el('div', 'coss-dt-head');
        head.appendChild(opts.onPrev ? btn('coss-dt-nav', '‹', opts.onPrev, '上个月') : el('span', 'coss-dt-nav-spacer'));

        if (opts.onYM) {
            var ym = el('div', 'coss-dt-ym');
            var ySel = el('select', 'coss-dt-select');
            ySel.setAttribute('aria-label', '选择年份');
            for (var y = Math.min(opts.y, opts.now.y) - 10; y <= Math.max(opts.y, opts.now.y) + 15; y++) {
                var yo = el('option', '', y + '年');
                yo.value = String(y);
                yo.selected = y === opts.y;
                ySel.appendChild(yo);
            }
            ySel.addEventListener('change', function () { opts.onYM(+ySel.value, opts.m); });
            var mSel = el('select', 'coss-dt-select');
            mSel.setAttribute('aria-label', '选择月份');
            for (var m = 0; m < 12; m++) {
                var mo = el('option', '', (m + 1) + '月');
                mo.value = String(m);
                mo.selected = m === opts.m;
                mSel.appendChild(mo);
            }
            mSel.addEventListener('change', function () { opts.onYM(opts.y, +mSel.value); });
            ym.append(ySel, mSel);
            head.appendChild(ym);
        } else {
            head.appendChild(el('div', 'coss-dt-head-title', opts.y + '年 ' + (opts.m + 1) + '月'));
        }
        head.appendChild(opts.onNext ? btn('coss-dt-nav', '›', opts.onNext, '下个月') : el('span', 'coss-dt-nav-spacer'));
        cal.appendChild(head);

        var week = el('div', 'coss-dt-week');
        WEEKDAYS.forEach(function (w) { week.appendChild(el('div', 'coss-dt-wd', w)); });
        cal.appendChild(week);

        var days = el('div', 'coss-dt-days');
        var offset = (new Date(opts.y, opts.m, 1).getDay() + 6) % 7;
        var total = new Date(opts.y, opts.m + 1, 0).getDate();
        var prevTotal = new Date(opts.y, opts.m, 0).getDate();
        var todayK = dateKey(opts.now);

        for (var cell = 0; cell < 42; cell++) {
            var cy = opts.y, cm = opts.m, cd, out = false;
            if (cell < offset) {
                cd = prevTotal - offset + cell + 1;
                if (--cm < 0) { cm = 11; cy--; }
                out = true;
            } else if (cell >= offset + total) {
                cd = cell - offset - total + 1;
                if (++cm > 11) { cm = 0; cy++; }
                out = true;
            } else {
                cd = cell - offset + 1;
            }
            (function (st, isOut) {
                var k = dateKey(st);
                var cls = 'coss-dt-day' + (isOut ? ' coss-dt-day-out' : '') + (k === todayK ? ' coss-dt-day-today' : '');
                var extra = opts.dayClass ? opts.dayClass(st, k, isOut) : '';
                if (extra) { cls += ' ' + extra; }
                var b = btn(cls, String(st.d), function () { opts.onPick(st); });
                if (opts.onHover) { b.addEventListener('mouseenter', function () { opts.onHover(st); }); }
                days.appendChild(b);
            })({ y: cy, m: cm, d: cd }, out);
        }
        cal.appendChild(days);
        return cal;
    }

    function openSingleDate(ctx) {
        if (ctx.input.disabled || ctx.input.readOnly || !openPop(ctx, '选择日期')) { return; }
        var now = nowLocal();
        var draft = parseDate(VALUE_DESC.get.call(ctx.input));
        var viewY = draft ? draft.y : now.y;
        var viewM = draft ? draft.m : now.m;

        function stepMonth(delta) {
            viewM += delta;
            if (viewM < 0) { viewM = 11; viewY--; } else if (viewM > 11) { viewM = 0; viewY++; }
            render();
        }
        function commit(st) {
            VALUE_DESC.set.call(ctx.input, fmtDate(st));
            ctx.sync();
            ctx.input.dispatchEvent(new Event('input', { bubbles: true }));
            ctx.input.dispatchEvent(new Event('change', { bubbles: true }));
            close();
        }
        function render() {
            pop.innerHTML = '';
            var selK = dateKey(draft);
            pop.appendChild(buildMonthGrid({
                y: viewY, m: viewM, now: now,
                onPrev: function () { stepMonth(-1); },
                onNext: function () { stepMonth(1); },
                onYM: function (ny, nm) { viewY = ny; viewM = nm; render(); },
                dayClass: function (st, k) { return k === selK ? 'coss-dt-day-active' : ''; },
                onPick: function (st) { draft = st; viewY = st.y; viewM = st.m; render(); }
            }));
            pop.appendChild(makeFoot(
                ctx.input.required ? null : function () { draft = null; render(); },
                [
                    btn('btn btn-sm btn-outline-secondary', '今天', function () { draft = { y: now.y, m: now.m, d: now.d }; viewY = now.y; viewM = now.m; render(); }),
                    btn('btn btn-sm btn-primary', '确定', function () { commit(draft); })
                ]
            ));
            placePop(pop, ctx.trigger);
        }
        render();
    }

    function openDateRange(ctx) {
        if (!openPop(ctx, '选择日期范围')) { return; }
        var now = nowLocal();
        var start = parseDate(VALUE_DESC.get.call(ctx.startInput));
        var end = parseDate(VALUE_DESC.get.call(ctx.endInput));
        var picking = false;
        var hover = null;
        var base = start || end || now;
        var viewY = base.y;
        var viewM = base.m;

        function stepMonth(delta) {
            viewM += delta;
            if (viewM < 0) { viewM = 11; viewY--; } else if (viewM > 11) { viewM = 0; viewY++; }
            render();
        }
        function commit(s, e) {
            VALUE_DESC.set.call(ctx.startInput, fmtDate(s));
            VALUE_DESC.set.call(ctx.endInput, fmtDate(e));
            ctx.sync();
            ctx.startInput.dispatchEvent(new Event('input', { bubbles: true }));
            ctx.endInput.dispatchEvent(new Event('input', { bubbles: true }));
            ctx.endInput.dispatchEvent(new Event('change', { bubbles: true }));
            close();
        }
        function render() {
            pop.innerHTML = '';
            var dual = window.innerWidth >= 600;
            var ny = viewY, nm = viewM + 1;
            if (nm > 11) { nm = 0; ny++; }
            var months = el('div', 'coss-dt-months' + (dual ? ' coss-dt-months-dual' : ''));

            var kA = dateKey(start);
            var kB = dateKey(picking ? (hover || start) : end);
            if (kA && kB && kA > kB) { var tmp = kA; kA = kB; kB = tmp; }

            function inMain(st) {
                return (st.y === viewY && st.m === viewM) || (dual && st.y === ny && st.m === nm);
            }
            function dayClass(st, k, isOut) {
                if (!kA || (isOut && inMain(st))) { return ''; }
                if (!kB || (k === kA && k === kB)) { return k === kA ? 'coss-dt-day-range-start coss-dt-day-range-end' : ''; }
                if (k === kA) { return 'coss-dt-day-range-start'; }
                if (k === kB) { return 'coss-dt-day-range-end'; }
                return k > kA && k < kB ? 'coss-dt-day-in-range' : '';
            }
            function onPick(st) {
                if (!picking) {
                    start = st; end = null; hover = st; picking = true;
                } else {
                    var a = start, b = st;
                    if (dateKey(a) > dateKey(b)) { var t = a; a = b; b = t; }
                    start = a; end = b; hover = null; picking = false;
                }
                render();
            }
            function onHover(st) {
                if (!picking || dateKey(hover) === dateKey(st)) { return; }
                hover = st;
                render();
            }

            months.appendChild(buildMonthGrid({
                y: viewY, m: viewM, now: now,
                onPrev: function () { stepMonth(-1); },
                onNext: dual ? null : function () { stepMonth(1); },
                onYM: dual ? null : function (y, m) { viewY = y; viewM = m; render(); },
                dayClass: dayClass, onPick: onPick, onHover: onHover
            }));
            if (dual) {
                months.appendChild(buildMonthGrid({
                    y: ny, m: nm, now: now,
                    onNext: function () { stepMonth(1); },
                    dayClass: dayClass, onPick: onPick, onHover: onHover
                }));
            }
            pop.appendChild(months);

            var endBtns = [];
            if (ctx.mode === 'validity') {
                endBtns.push(btn('btn btn-sm btn-outline-secondary', ctx.openEnd || '长期有效', function () {
                    if (!start) { start = { y: now.y, m: now.m, d: now.d }; }
                    end = null; picking = false; hover = null; viewY = start.y; viewM = start.m; render();
                }));
            } else {
                [
                    ['本月', { y: now.y, m: now.m, d: 1 }, { y: now.y, m: now.m, d: new Date(now.y, now.m + 1, 0).getDate() }],
                    ['近 7 天', addDays(now, -6), now],
                    ['近 30 天', addDays(now, -29), now]
                ].forEach(function (p) {
                    endBtns.push(btn('btn btn-sm btn-outline-secondary', p[0], function () {
                        start = p[1]; end = p[2]; picking = false; hover = null; viewY = start.y; viewM = start.m; render();
                    }));
                });
            }
            endBtns.push(btn('btn btn-sm btn-primary', '确定', function () { commit(start, end); }));
            pop.appendChild(makeFoot(function () { start = null; end = null; picking = false; hover = null; render(); }, endBtns));
            placePop(pop, ctx.trigger);
        }
        render();
    }

    function enhanceRange(root) {
        if (root.dataset.cossPickerReady) { return; }
        var inputs = root.querySelectorAll('input[type="date"]');
        if (inputs.length < 2) { return; }
        root.dataset.cossPickerReady = '1';
        var startInput = inputs[0], endInput = inputs[1];
        [startInput, endInput].forEach(function (inp) {
            inp.dataset.cossPickerReady = '1';
            inp.classList.add('coss-dt-native');
            inp.tabIndex = -1;
        });

        var trigger = btn('form-control coss-dt-trigger', null, null, root.dataset.placeholder || '选择日期范围');
        trigger.setAttribute('aria-haspopup', 'dialog');
        trigger.setAttribute('aria-expanded', 'false');
        var textEl = el('span', 'coss-dt-text');
        trigger.append(makeCalIcon(), textEl);
        root.appendChild(trigger);

        var openEnd = root.dataset.openEnd || '';
        function sync() {
            var s = VALUE_DESC.get.call(startInput), e = VALUE_DESC.get.call(endInput);
            var shown = s && e ? s + ' – ' + e : (s ? (openEnd ? s + ' – ' + openEnd : s + ' 起') : (e ? '至 ' + e : ''));
            textEl.textContent = shown || root.dataset.placeholder || '选择日期范围';
            textEl.classList.toggle('coss-dt-placeholder', !shown);
        }
        hookValue(startInput, sync);
        hookValue(endInput, sync);

        var ctx = { startInput: startInput, endInput: endInput, trigger: trigger, sync: sync, mode: root.dataset.rangeMode || '', openEnd: openEnd };
        trigger.addEventListener('click', function (e) { e.stopPropagation(); openDateRange(ctx); });
        sync();
    }

    function enhanceSingle(input) {
        if (input.dataset.cossPickerReady) { return; }
        input.dataset.cossPickerReady = '1';

        var wrap = el('div', 'coss-dt');
        ['w-0', 'flex-fill'].forEach(function (c) { if (input.classList.contains(c)) { wrap.classList.add(c); } });
        var triggerCls = 'form-control coss-dt-trigger'
            + (input.classList.contains('form-control-sm') ? ' form-control-sm' : '')
            + (input.classList.contains('is-invalid') ? ' is-invalid' : '');
        var trigger = btn(triggerCls, null, null, input.getAttribute('aria-label'));
        trigger.disabled = input.disabled;
        trigger.setAttribute('aria-haspopup', 'dialog');
        trigger.setAttribute('aria-expanded', 'false');
        var textEl = el('span', 'coss-dt-text');
        trigger.append(makeCalIcon(), textEl);

        input.parentNode.insertBefore(wrap, input);
        input.classList.add('coss-dt-native');
        input.tabIndex = -1;
        wrap.append(input, trigger);

        var placeholder = input.getAttribute('placeholder') || input.getAttribute('aria-label') || '选择日期';
        function sync() {
            var shown = fmtDate(parseDate(VALUE_DESC.get.call(input)));
            textEl.textContent = shown || placeholder;
            textEl.classList.toggle('coss-dt-placeholder', !shown);
            trigger.disabled = input.disabled;
        }
        hookValue(input, sync);

        var ctx = { input: input, trigger: trigger, sync: sync };
        trigger.addEventListener('click', function (e) { e.stopPropagation(); openSingleDate(ctx); });
        input.addEventListener('focus', function () { input.blur(); trigger.focus(); });
        if (input.id) {
            var lbl = document.querySelector('label[for="' + CSS.escape(input.id) + '"]');
            if (lbl) { lbl.addEventListener('click', function (e) { e.preventDefault(); e.stopPropagation(); openSingleDate(ctx); }); }
        }
        input.addEventListener('change', sync);
        input.addEventListener('input', sync);
        sync();
    }

    function enhanceTimeRange(root) {
        if (root.dataset.cossPickerReady) { return; }
        var startInput = root.querySelector('[data-tr-start]');
        var endInput = root.querySelector('[data-tr-end]');
        var panel = root.querySelector('[data-tr-panel]');
        var label = root.querySelector('[data-cheap-label]');
        var trigger = root.querySelector('[data-bs-toggle="dropdown"]');
        if (!startInput || !endInput || !panel) { return; }
        root.dataset.cossPickerReady = '1';

        var start = { h: 0, min: 0 }, end = { h: 8, min: 0 }, hasValue = false, editing = 'start';
        function toMins(t) { return t.h * 60 + t.min; }
        function fromMins(m) {
            var n = ((Math.round(m) % 1440) + 1440) % 1440;
            return { h: Math.floor(n / 60), min: n % 60 };
        }
        function snapMins(clientX, rail) {
            var rect = rail.getBoundingClientRect();
            if (!rect.width) { return -1; }
            var ratio = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
            return Math.min(1410, Math.round((ratio * 1440) / 30) * 30);
        }
        function durationLabel(s, e) {
            var sm = toMins(s), em = toMins(e), cross = em <= sm;
            var diff = cross ? (1440 - sm + em) : (em - sm);
            var hrs = Math.floor(diff / 60), mins = diff % 60;
            return (cross && em < sm ? '跨日 · ' : '') + ((hrs ? hrs + ' 小时' : '') + (mins ? (hrs ? ' ' : '') + mins + ' 分' : '') || '24 小时');
        }
        function loadFromInputs() {
            var sVal = parseTime(startInput.value), eVal = parseTime(endInput.value);
            hasValue = !!(sVal || eVal);
            start = sVal || { h: 0, min: 0 };
            end = eVal || { h: 8, min: 0 };
            editing = 'start';
        }
        function sync() {
            var sv = hasValue ? fmtTime(start) : '', ev = hasValue ? fmtTime(end) : '';
            startInput.value = sv;
            endInput.value = ev;
            if (label) { label.textContent = hasValue ? (sv + '–' + ev) : '便宜时段'; }
        }
        function addFill(rail, left, width) {
            var f = el('div', 'coss-tr-fill');
            f.style.left = left + '%';
            f.style.width = width + '%';
            rail.appendChild(f);
        }

        function render() {
            panel.innerHTML = '';
            var summary = el('div', 'coss-tr-summary');
            summary.appendChild(el('div', hasValue ? 'coss-tr-range-text' : 'coss-tr-range-empty', hasValue ? (fmtTime(start) + ' – ' + fmtTime(end)) : '未设置便宜时段'));
            if (hasValue) { summary.appendChild(el('div', 'coss-tr-duration', durationLabel(start, end))); }
            panel.appendChild(summary);

            var timeline = el('div', 'coss-tr-timeline');
            var track = el('div', 'coss-tr-track');
            var railWrap = el('div', 'coss-tr-rail-wrap');
            var rail = el('div', 'coss-tr-rail');
            if (hasValue) {
                var sp = (toMins(start) / 1440) * 100, ep = (toMins(end) / 1440) * 100;
                if (sp < ep) { addFill(rail, sp, ep - sp); }
                else { addFill(rail, sp, 100 - sp); addFill(rail, 0, ep); }
            }
            railWrap.appendChild(rail);
            track.appendChild(railWrap);

            function makeThumb(which, val, aria) {
                var th = btn('coss-tr-thumb' + (hasValue ? (editing === which ? ' coss-tr-thumb-active' : '') : ' coss-tr-thumb-idle'), null, null, aria);
                th.style.left = ((toMins(val) / 1440) * 100) + '%';
                th.addEventListener('pointerdown', function (e) {
                    e.preventDefault(); e.stopPropagation();
                    editing = which; hasValue = true;
                    var last = -1;
                    function move(ev) {
                        var snapped = snapMins(ev.clientX, rail);
                        if (snapped < 0 || snapped === last) { return; }
                        last = snapped;
                        if (which === 'start') { start = fromMins(snapped); } else { end = fromMins(snapped); }
                        render();
                    }
                    function up() {
                        window.removeEventListener('pointermove', move);
                        window.removeEventListener('pointerup', up);
                        window.removeEventListener('pointercancel', up);
                    }
                    window.addEventListener('pointermove', move);
                    window.addEventListener('pointerup', up);
                    window.addEventListener('pointercancel', up);
                });
                return th;
            }
            railWrap.append(makeThumb('start', start, '拖动调整开始时间'), makeThumb('end', end, '拖动调整结束时间'));

            track.addEventListener('click', function (e) {
                if (e.target.classList.contains('coss-tr-thumb')) { return; }
                var snapped = snapMins(e.clientX, rail);
                if (snapped < 0) { return; }
                editing = Math.abs(snapped - toMins(start)) <= Math.abs(snapped - toMins(end)) ? 'start' : 'end';
                hasValue = true;
                if (editing === 'start') { start = fromMins(snapped); } else { end = fromMins(snapped); }
                render();
            });

            var ticks = el('div', 'coss-tr-ticks');
            ['00', '06', '12', '18', '24'].forEach(function (t) { ticks.appendChild(el('span', '', t)); });
            timeline.append(track, ticks);
            panel.appendChild(timeline);

            var presets = el('div', 'coss-tr-presets');
            [
                ['00:00–06:00', 0, 6], ['00:00–08:00', 0, 8],
                ['01:00–07:00', 1, 7], ['23:00–07:00', 23, 7]
            ].forEach(function (p) {
                var isCur = hasValue && start.h === p[1] && start.min === 0 && end.h === p[2] && end.min === 0;
                presets.appendChild(btn('coss-tr-preset' + (isCur ? ' coss-tr-preset-active' : ''), p[0], function () {
                    start = { h: p[1], min: 0 }; end = { h: p[2], min: 0 }; hasValue = true; render();
                }));
            });
            panel.appendChild(presets);

            var ok = btn('btn btn-sm btn-primary', '确定', sync);
            ok.dataset.cheapDone = '';
            panel.appendChild(makeFoot(function () { hasValue = false; render(); }, [ok]));
        }

        panel.addEventListener('click', function (e) {
            if (!e.target.closest('[data-cheap-done]')) { e.stopPropagation(); }
        });
        if (trigger) { trigger.addEventListener('click', function () { loadFromInputs(); render(); }); }
        loadFromInputs();
        render();
    }

    document.querySelectorAll('[data-date-range]').forEach(enhanceRange);
    document.querySelectorAll('[data-time-range]').forEach(enhanceTimeRange);
    document.querySelectorAll('input[type="date"]').forEach(enhanceSingle);

    function onDocClick(e) {
        if (pop && active && !pop.contains(e.target) && !active.trigger.contains(e.target)) { close(); }
    }
    function onKey(e) {
        if (e.key === 'Escape' && active) { var t = active.trigger; close(); t.focus(); }
    }
    function onReposition() {
        if (pop && active) { placePop(pop, active.trigger); }
    }

    document.addEventListener('click', onDocClick);
    document.addEventListener('keydown', onKey);
    window.addEventListener('resize', onReposition);
    window.addEventListener('scroll', onReposition, true);

    return function () {
        close();
        document.removeEventListener('click', onDocClick);
        document.removeEventListener('keydown', onKey);
        window.removeEventListener('resize', onReposition);
        window.removeEventListener('scroll', onReposition, true);
    };
});
