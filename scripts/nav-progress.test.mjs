// 无第三方依赖：CHROME_BIN 可指定 Chromium 路径。
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createServer } from 'node:http';
import { existsSync, readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';

const chromeBin = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const root = new URL('../', import.meta.url);
const layout = readFileSync(new URL('web/templates/layout.html', root), 'utf8');
const scripts = layout.slice(layout.indexOf('<script type="module" src="/static/tabler/'));
const cheapButtonTemplate = readFileSync(new URL('web/templates/node_form.html', root), 'utf8').match(/<button[^\n]*title="便宜时段">[^\n]*<\/button>/)[0];
const cheapButtons = '<div class="placement-fields">' + [false, true].map(set => '<div class="dropdown" data-cheap>' + cheapButtonTemplate
  .replace(/\{\{if not \$cheap\}\} text-secondary\{\{end\}\}/, set ? '' : ' text-secondary')
  .replace(/\{\{if \$cheap\}\}.*?\{\{end\}\}/, set ? '01:00–06:00' : '便宜时段') + '<div class="dropdown-menu">时段设置</div></div>').join('') + '</div>';
const progress = layout.match(/<div id="nav-progress"[\s\S]*?<\/div>\s*<\/div>/)[0];
const content = `<textarea data-json-editor>{"ok":true}</textarea><select data-search><option value="1">一</option><option value="2">二</option></select>
<form method="post" action="/admin/saved"><button type="submit">保存</button></form>`;
function html(name, extra = '') {
  return `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>${name}</title>
<link rel="stylesheet" href="/static/tabler/css/tabler.min.css"><link rel="stylesheet" href="/static/css/app.css">
<body><div class="page"><header class="navbar">${progress}<div class="container-xl"><a href="/admin/a">A</a><a href="/admin/b">B</a><a href="/admin/slow">慢页面</a></div></header>
<div class="page-wrapper"><h2 class="page-title">${name}</h2>${content}${extra}${'<p>内容</p>'.repeat(100)}</div></div>${scripts}`;
}

function batchContent(kind) {
  return `<button data-${kind}-select-mode type="button">批量选择</button>
<div class="${kind}-list"><input type="checkbox" data-${kind}-select-all>
<input type="checkbox" data-${kind}-select value="11" data-name="节点甲"><input type="checkbox" data-${kind}-select value="22" data-name="节点乙"></div>
<div class="fixed-bottom d-none" data-batch-kind="${kind}"><span data-batch-count>0</span><button data-batch-clear type="button">取消</button></div>
<form method="post" action="/admin/saved" data-batch-form data-confirm="确定批量部署？"><button type="submit">部署</button></form>`;
}

test('Tabler 局部导航浏览器回归', { skip: !existsSync(chromeBin), timeout: 60000 }, async t => {
  let posts = 0;
  const postBodies = [];
  const errors = [];
  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname.startsWith('/static/')) {
      const file = new URL('web' + url.pathname, root);
      res.setHeader('Content-Type', url.pathname.endsWith('.js') ? 'text/javascript' : 'text/css');
      try { res.end(readFileSync(file)); } catch (_) { res.writeHead(404).end(); }
      return;
    }
    const name = url.pathname.split('/').pop();
    res.setHeader('Content-Type', 'text/html; charset=utf-8');
    if (req.method === 'POST') {
      let body = '';
      req.on('data', chunk => { body += chunk; });
      req.on('end', () => { posts++; postBodies.push(body); res.end(html('saved')); });
      return;
    }
    if (name === 'failed' && req.headers['sec-fetch-dest'] !== 'document') { res.writeHead(503).end('失败'); return; }
    if (name === 'expired') { res.writeHead(302, { Location: '/login' }).end(); return; }
    if (name === 'login') { res.end('<title>登录</title><p>登录</p>'); return; }
    let body = html(name, name === 'node-batch' ? batchContent('node') : name === 'account-batch' ? batchContent('account') : name === 'cheap-button' ? cheapButtons : '');
    if (name === 'changed') body = body.replace('/static/css/app.css', '/static/css/app.css?v=changed');
    if (name === 'slow') setTimeout(() => res.end(body), 900);
    else res.end(body);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const profile = mkdtempSync(join(tmpdir(), 'airopscat-nav-test-'));
  const chrome = spawn(chromeBin, ['--headless=new', '--no-first-run', '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] });
  let ws;
  try {
    const endpoint = await new Promise((resolve, reject) => {
      let output = '';
      chrome.stderr.on('data', chunk => { output += chunk; const match = output.match(/DevTools listening on (ws:\/\/\S+)/); if (match) resolve(match[1]); });
      chrome.on('error', reject);
      chrome.on('exit', () => reject(new Error('Chrome 提前退出')));
    });
    ws = new WebSocket(endpoint);
    await new Promise(resolve => ws.addEventListener('open', resolve));
    let id = 0;
    const pending = new Map();
    ws.addEventListener('message', event => {
      const message = JSON.parse(event.data);
      if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails);
      if (!message.id) return;
      const callback = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) callback.reject(new Error(JSON.stringify(message.error)));
      else callback.resolve(message.result);
    });
    const send = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
      const request = ++id;
      pending.set(request, { resolve, reject });
      ws.send(JSON.stringify({ id: request, method, params, sessionId }));
    });
    const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
    const call = (method, params) => send(method, params, sessionId);
    const evaluate = async expression => {
      const result = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
      if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
      return result.result.value;
    };
    const waitFor = async expression => {
      const deadline = Date.now() + 6000;
      while (Date.now() < deadline) {
        try { if (await evaluate(expression)) return; } catch (error) {
          if (!/context|Cannot find/i.test(error.message)) throw error;
        }
        await new Promise(resolve => setTimeout(resolve, 25));
      }
      throw new Error('等待超时：' + expression);
    };
    const open = async path => {
      await call('Page.navigate', { url: origin + path });
      await waitFor(`location.pathname === ${JSON.stringify(path)} && document.readyState === 'complete' && !!window.AirOpsPage`);
    };
    const click = async path => {
      await evaluate(`{const a=document.createElement('a');a.href=${JSON.stringify(path)};document.querySelector('.page-wrapper').append(a);a.click();a.remove();}`);
    };
    const ready = name => waitFor(`document.title === ${JSON.stringify(name)} && document.getElementById('nav-progress')?.classList.contains('d-none')`);
    await call('Runtime.enable');
    await call('Page.enable');
    await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });

    await t.test('慢请求期间动画持续，切换不卸载文档，使用原生 Tabler 样式', async () => {
      await open('/admin/a');
      await evaluate('window.marker=42; window.originalBar=document.getElementById("nav-progress"); window.originalHeader=document.querySelector("header")');
      await click('/admin/slow');
      assert.equal(await evaluate('originalBar.classList.contains("d-none")'), false);
      const sample = await evaluate(`new Promise(resolve=>setTimeout(()=>{const bar=originalBar.querySelector('.progress-bar');const first=getComputedStyle(bar,'::before').left;setTimeout(()=>resolve({first,second:getComputedStyle(bar,'::before').left,position:getComputedStyle(originalBar).position,height:originalBar.offsetHeight,width:originalBar.offsetWidth,overflow:document.documentElement.scrollWidth>innerWidth}),180)},100))`);
      assert.notEqual(sample.first, sample.second);
      assert.equal(sample.position, 'fixed');
      assert.equal(sample.height, 4);
      assert.equal(sample.width, 390);
      assert.equal(sample.overflow, false);
      await ready('slow');
      assert.equal(await evaluate('marker===42 && originalBar===document.getElementById("nav-progress") && originalHeader===document.querySelector("header")'), true);
      assert.equal(await evaluate('document.activeElement.classList.contains("page-title")'), true);
    });
    await t.test('反复切换重新初始化编辑器和选择框，不重复包装', async () => {
      for (const name of ['b', 'a', 'b']) {
        await click('/admin/' + name); await ready(name);
        assert.deepEqual(await evaluate('[document.querySelectorAll(".je").length,document.querySelectorAll(".ts-wrapper").length,!!document.querySelector("select").tomselect]'), [1, 1, true]);
        assert.equal(await evaluate(`document.querySelector('textarea').value='{"x":2}';document.querySelector('[data-act="format"]').click();document.querySelector('textarea').value.includes('\\n')`), true);
      }
    });
    await t.test('浏览器后退前进保留文档，恢复滚动位置', async () => {
      await evaluate('scrollTo({top:400,behavior:"instant"})');
      await click('/admin/a'); await ready('a');
      await evaluate('history.back()'); await ready('b');
      assert.equal(await evaluate('scrollY'), 400);
      assert.equal(await evaluate('marker'), 42);
      await evaluate('history.forward()'); await ready('a');
      assert.equal(await evaluate('scrollY'), 0);
    });
    await t.test('刷新页面恢复滚动位置', async () => {
      await evaluate('scrollTo({top:300,behavior:"instant"})');
      await call('Page.reload');
      await waitFor('document.readyState === "complete" && !!window.AirOpsPage && scrollY === 300');
      await evaluate('window.marker=42');
    });
    await t.test('快速连续导航不会让旧响应覆盖新页面', async () => {
      await click('/admin/slow'); await click('/admin/b'); await ready('b');
      await evaluate('new Promise(resolve=>setTimeout(resolve,1000))');
      assert.equal(await evaluate('document.title'), 'b');
      assert.equal(await evaluate('marker'), 42);
    });
    await t.test('取消点击和新窗口点击不触发 fetch', async () => {
      const count = await evaluate(`{let count=0;const original=window.fetch;window.fetch=(...args)=>{count++;return original(...args)};const a=document.querySelector('a');a.addEventListener('click',e=>e.preventDefault(),{once:true});a.click();a.dispatchEvent(new MouseEvent('click',{bubbles:true,ctrlKey:true,button:0}));window.fetch=original;count;}`);
      assert.equal(count, 0);
    });
    await t.test('表单保持原生 POST，仅提交一次', async () => {
      await evaluate('document.querySelector("form").requestSubmit()'); await ready('saved');
      assert.equal(posts, 1);
      assert.equal(await evaluate('window.marker'), undefined);
    });
    await t.test('批量选择跨页复用，取消确认后提交最新 ID，节点不重复确认', async () => {
      await open('/admin/a');
      await evaluate('window.marker=42;window.nativeConfirms=0;window.confirm=()=>{nativeConfirms++;return true}');
      for (const kind of ['node', 'account', 'node']) {
        await click('/admin/' + kind + '-batch'); await ready(kind + '-batch');
        assert.equal(await evaluate('marker'), 42);
        await evaluate(`document.querySelector('[data-${kind}-select-mode]').click();document.querySelector('[data-${kind}-select-all]').click()`);
        assert.deepEqual(await evaluate(`[document.querySelector('[data-batch-count]').textContent, document.querySelector('[data-batch-kind]').classList.contains('d-none'), !!document.body.style.paddingBottom]`), ['2', false, true]);
        await evaluate(`document.querySelector('[data-batch-clear]').click()`);
        assert.equal(await evaluate(`document.querySelectorAll('[data-${kind}-select]:checked').length`), 0);
        assert.equal(await evaluate('document.body.style.paddingBottom'), '');
        await evaluate(`document.querySelector('[data-${kind}-select-mode]').click();document.querySelector('[data-${kind}-select-all]').click();document.querySelector('[data-batch-form]').requestSubmit()`);
        await waitFor(`!!document.querySelector('[data-confirm-ok]')?.closest('.modal.show')`);
        if (kind === 'node') assert.match(await evaluate(`document.querySelector('[data-confirm-text]').textContent`), /节点甲、节点乙/);
        await waitFor(`!document.querySelector('[data-confirm-ok]').closest('.modal').classList.contains('showing')`);
        // 等待 Bootstrap 过渡完成后取消，不发出任何 POST。
        await evaluate(`new Promise(resolve=>setTimeout(resolve,350))`);
        const before = posts;
        await evaluate(`document.querySelector('[data-confirm-ok]').closest('.modal').querySelector('[data-bs-dismiss]').click()`);
        await waitFor(`!document.querySelector('.modal-backdrop')`);
        assert.equal(posts, before);
        assert.equal(await evaluate(`document.querySelector('[data-batch-form]').hasAttribute('data-confirmed')`), false);
        await evaluate(`document.querySelectorAll('[data-${kind}-select]')[1].click();document.querySelector('[data-batch-form]').requestSubmit()`);
        await waitFor(`!!document.querySelector('[data-confirm-ok]')?.closest('.modal.show')`);
        await evaluate(`new Promise(resolve=>setTimeout(resolve,350))`);
        if (kind === 'node') assert.doesNotMatch(await evaluate(`document.querySelector('[data-confirm-text]').textContent`), /节点乙/);
        // 防止导航，用真实 POST 发给服务端，断言收到的字段与当前选择一致。
        await evaluate(`window.batchPosted=null;document.querySelector('[data-batch-form]').addEventListener('submit',event=>{event.preventDefault();window.batchPosted=fetch(event.target.action,{method:'POST',body:new URLSearchParams(new FormData(event.target))}).then(()=>true)});document.querySelector('[data-confirm-ok]').click()`);
        await waitFor('window.batchPosted');
        await evaluate('window.batchPosted');
        assert.equal(posts, before + 1);
        assert.deepEqual(new URLSearchParams(postBodies.at(-1)).getAll(kind + '_ids'), ['11']);
        assert.equal(await evaluate('nativeConfirms'), 0);
        await waitFor(`!document.querySelector('.modal-backdrop')`);
        await click('/admin/a'); await ready('a');
        assert.equal(await evaluate('document.body.style.paddingBottom'), '');
      }
    });
    await t.test('隐藏页签校验由公共监听处理，局部导航后只触发一次', async () => {
      for (const name of ['a', 'b']) {
        await click('/admin/' + name); await ready(name);
        const result = await evaluate(`{
          const root=document.querySelector('.page-wrapper');
          root.insertAdjacentHTML('beforeend','<div class="nav nav-tabs" role="tablist"><button type="button" data-bs-toggle="tab" data-bs-target="#validation-pane" role="tab">页签</button></div><form><div class="tab-content"><div class="tab-pane" id="validation-pane"><input required></div></div></form>');
          let clicks=0;const trigger=root.querySelector('[data-bs-target="#validation-pane"]');
          trigger.addEventListener('click',()=>{clicks++});
          document.querySelector('#validation-pane input').checkValidity();
          [clicks,document.getElementById('validation-pane').classList.contains('active')];
        }`);
        assert.deepEqual(result, [1, true]);
      }
    });
    await t.test('HTTP 错误和资源版本变化回退整页导航', async () => {
      for (const name of ['failed', 'changed']) {
        await open('/admin/a'); await evaluate('window.marker=42');
        await click('/admin/' + name); await ready(name);
        assert.equal(await evaluate('window.marker'), undefined);
      }
    });
    await t.test('等待超时后回退整页导航，不残留进度条', async () => {
      await open('/admin/a'); await evaluate('window.marker=42;const timeout=window.setTimeout;window.setTimeout=(fn,ms,...args)=>timeout(fn,ms===15000?50:ms,...args)');
      await click('/admin/slow'); await ready('slow');
      assert.equal(await evaluate('window.marker'), undefined);
    });
    await t.test('原生离开保护启用时不接管链接', async () => {
      await open('/admin/a'); await evaluate('window.marker=42;document.body.dataset.navigationNative=""');
      await click('/admin/b'); await ready('b');
      assert.equal(await evaluate('window.marker'), undefined);
    });
    await t.test('便宜时段按钮在明暗主题下悬浮和展开时文字均可读', async () => {
      await open('/admin/cheap-button');
      for (const theme of ['light', 'dark']) {
        await evaluate(`document.documentElement.dataset.bsTheme=${JSON.stringify(theme)}`);
        for (let index = 0; index < 2; index++) {
          const point = await evaluate(`{const r=document.querySelectorAll('[data-cheap] > button')[${index}].getBoundingClientRect();({x:r.x+r.width/2,y:r.y+r.height/2})}`);
          await call('Input.dispatchMouseEvent', { type: 'mouseMoved', ...point });
          await evaluate('new Promise(resolve=>setTimeout(resolve,200))');
          for (const expanded of [false, true]) {
            if (expanded) await evaluate(`document.querySelectorAll('[data-cheap] > button')[${index}].click()`);
            const contrast = await evaluate(`{
              const button=document.querySelectorAll('[data-cheap] > button')[${index}];
              const style=getComputedStyle(button);
              const context=document.createElement('canvas').getContext('2d');
              const paint=color=>{context.fillStyle=color;context.fillRect(0,0,1,1)};
              const luminance=()=>{const rgb=[...context.getImageData(0,0,1,1).data].slice(0,3).map(v=>{v/=255;return v<=.04045?v/12.92:((v+.055)/1.055)**2.4});return rgb[0]*.2126+rgb[1]*.7152+rgb[2]*.0722};
              const parents=[];for(let el=button;el;el=el.parentElement)parents.unshift(el);
              paint('white');parents.forEach(el=>paint(getComputedStyle(el).backgroundColor));
              const b=luminance();paint(style.color);const a=luminance();
              ({ratio:(Math.max(a,b)+.05)/(Math.min(a,b)+.05),color:style.color,background:style.backgroundColor});
            }`);
            assert.ok(contrast.ratio >= 4.5, theme + ' ' + (index ? '已设置' : '未设置') + ' ' + (expanded ? '展开' : '悬浮') + ' 对比度不足：' + JSON.stringify(contrast));
          }
          await evaluate(`document.querySelectorAll('[data-cheap] > button')[${index}].click()`);
        }
      }
    });
    await t.test('登录失效跳转到登录页', async () => {
      await open('/admin/a'); await click('/admin/expired');
      await waitFor('location.pathname === "/login" && document.title === "登录"');
    });
    assert.deepEqual(errors, [], '浏览器不应出现未捕获异常');
  } finally {
    ws?.close();
    const exited = new Promise(resolve => chrome.once('exit', resolve));
    chrome.kill();
    server.closeAllConnections(); server.close();
    await exited;
    rmSync(profile, { recursive: true, force: true });
  }
});
