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
const progress = layout.match(/<div id="nav-progress"[\s\S]*?<\/div>\s*<\/div>/)[0];
const content = `<textarea data-json-editor>{"ok":true}</textarea><select data-search><option value="1">一</option><option value="2">二</option></select>
<form method="post" action="/admin/saved"><button type="submit">保存</button></form>`;
function html(name, extra = '') {
  return `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>${name}</title>
<link rel="stylesheet" href="/static/tabler/css/tabler.min.css"><link rel="stylesheet" href="/static/css/app.css">
<body><div class="page"><header class="navbar">${progress}<div class="container-xl"><a href="/admin/a">A</a><a href="/admin/b">B</a><a href="/admin/slow">慢页面</a></div></header>
<div class="page-wrapper"><h2 class="page-title">${name}</h2>${content}${extra}${'<p>内容</p>'.repeat(100)}</div></div>${scripts}`;
}

test('Tabler 局部导航浏览器回归', { skip: !existsSync(chromeBin), timeout: 60000 }, async t => {
  let posts = 0;
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
    if (req.method === 'POST') { posts++; res.end(html('saved')); return; }
    if (name === 'failed' && req.headers['sec-fetch-dest'] !== 'document') { res.writeHead(503).end('失败'); return; }
    if (name === 'expired') { res.writeHead(302, { Location: '/login' }).end(); return; }
    if (name === 'login') { res.end('<title>登录</title><p>登录</p>'); return; }
    let body = html(name);
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
