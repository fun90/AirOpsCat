// 为 textarea[data-json-editor] 增加：语法高亮、行号、实时校验、格式化/压缩/复制、Tab 缩进。不依赖第三方库。
(function () {
    var esc = function (s) { return s.replace(/&/g, '&amp;').replace(/</g, '&lt;'); };
    var TOKEN = /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false)\b|\bnull\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g;

    function highlight(text) {
        return esc(text).replace(TOKEN, function (m, str, colon, bool) {
            if (str) { return colon ? '<span class="je-key">' + str + '</span>' + colon : '<span class="je-str">' + str + '</span>'; }
            if (bool) { return '<span class="je-bool">' + m + '</span>'; }
            if (m === 'null') { return '<span class="je-null">' + m + '</span>'; }
            return '<span class="je-num">' + m + '</span>';
        });
    }

    // 只调整字符串外的空白，不经 JSON.stringify，避免大整数精度丢失或键顺序变化。
    function reformat(text, pretty) {
        var out = '', depth = 0, quoted = false, escaped = false;
        function newline() { return pretty ? '\n' + '  '.repeat(depth) : ''; }
        for (var i = 0; i < text.length; i++) {
            var c = text[i];
            if (quoted) {
                out += c;
                if (escaped) { escaped = false; }
                else if (c === '\\') { escaped = true; }
                else if (c === '"') { quoted = false; }
                continue;
            }
            if (/\s/.test(c)) { continue; }
            if (c === '"') { quoted = true; out += c; }
            else if (c === '{' || c === '[') { out += c; depth++; out += newline(); }
            else if (c === '}' || c === ']') { depth--; out += newline() + c; }
            else if (c === ',') { out += c + newline(); }
            else if (c === ':') { out += pretty ? ': ' : ':'; }
            else { out += c; }
        }
        return out;
    }

    function init(ta) {
        var root = document.createElement('div');
        root.className = 'je';
        root.innerHTML = '<div class="je-bar">' +
            '<button type="button" class="btn btn-sm btn-outline-secondary" data-act="format">格式化</button>' +
            '<button type="button" class="btn btn-sm btn-outline-secondary" data-act="minify">压缩</button>' +
            '<button type="button" class="btn btn-sm btn-outline-secondary" data-act="copy">复制</button>' +
            '<span class="je-status" role="status"></span></div>' +
            '<div class="je-body"><div class="je-gutter" aria-hidden="true"></div>' +
            '<div class="je-code"><pre class="je-hl" aria-hidden="true"></pre></div></div>';
        ta.parentNode.insertBefore(root, ta);
        var code = root.querySelector('.je-code');
        var hl = root.querySelector('.je-hl');
        var gutter = root.querySelector('.je-gutter');
        var status = root.querySelector('.je-status');
        ta.classList.add('je-ta');
        ta.removeAttribute('rows');
        ta.setAttribute('wrap', 'soft');
        ta.setAttribute('spellcheck', 'false');
        ta.setAttribute('aria-description', 'Tab 插入两个空格，Shift+Tab 离开编辑器');
        code.appendChild(ta);

        function validate() {
            var v = ta.value.trim();
            var msg = '';
            if (v !== '') {
                try { JSON.parse(v); } catch (e) { msg = e.message; }
            }
            ta.setCustomValidity(msg ? 'JSON 格式错误：' + msg : '');
            root.classList.toggle('je-invalid', !!msg);
            status.className = 'je-status ' + (msg ? 'bad' : 'ok');
            status.textContent = msg ? 'JSON 格式错误：' + msg : (v ? 'JSON 有效' : '');
            return !msg;
        }
        function render() {
            var lines = ta.value.split('\n');
            hl.innerHTML = lines.map(function (line) {
                return '<span class="je-line">' + (highlight(line) || ' ') + '</span>';
            }).join('');
            gutter.innerHTML = lines.map(function (_, i) { return '<div>' + (i + 1) + '</div>'; }).join('');
            layout();
            sync();
            validate();
        }
        function layout() {
            // textarea 的 clientWidth 不含滚动条，两个层必须使用相同的换行宽度。
            hl.style.width = ta.clientWidth + 'px';
            Array.from(hl.children).forEach(function (line, i) {
                gutter.children[i].style.height = line.getBoundingClientRect().height + 'px';
            });
            sync();
        }
        new ResizeObserver(layout).observe(ta);
        function sync() {
            hl.scrollTop = ta.scrollTop; hl.scrollLeft = ta.scrollLeft; gutter.scrollTop = ta.scrollTop;
        }
        function set(text) { ta.value = text; render(); }

        root.querySelector('.je-bar').addEventListener('click', function (e) {
            var act = e.target.getAttribute && e.target.getAttribute('data-act');
            if (!act) { return; }
            if (act === 'copy') {
                if (!navigator.clipboard) {
                    status.className = 'je-status bad'; status.textContent = '无法访问剪贴板，请手动复制';
                    return;
                }
                navigator.clipboard.writeText(ta.value).then(function () {
                    status.className = 'je-status ok'; status.textContent = '已复制';
                }).catch(function () {
                    status.className = 'je-status bad'; status.textContent = '复制失败，请手动复制';
                });
                return;
            }
            if (!validate()) { return; }
            if (ta.value.trim() === '') { return; }
            set(reformat(ta.value, act === 'format'));
        });
        ta.addEventListener('input', render);
        ta.addEventListener('scroll', sync);
        ta.addEventListener('keydown', function (e) {
            if (e.key === 'Tab' && !e.shiftKey && !e.ctrlKey && !e.metaKey && !e.altKey) {
                e.preventDefault();
                var s = ta.selectionStart, t = ta.selectionEnd;
                ta.setRangeText('  ', s, t, 'end');
                render();
            }
        });
        // 初始内容若是合法 JSON 就先格式化（保持键顺序不变的话需服务端已格式化，这里只处理单行内容）
        var v = ta.value.trim();
        if (v && v.indexOf('\n') < 0) { try { JSON.parse(v); ta.value = reformat(v, true); } catch (e) { /* 保持原样，由校验提示 */ } }
        render();
    }

    document.querySelectorAll('textarea[data-json-editor]').forEach(init);
})();
