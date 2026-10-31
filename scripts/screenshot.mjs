// 用本地实例的管理员令牌截取整页，并检查横向溢出；先运行 scripts/local-prod.sh。
// 用法：node scripts/screenshot.mjs <路径或 URL> [宽度=390] [输出=/tmp/airopscat-shot.png]
// 有横向溢出时退出码为 1；截图含生产数据，看完即删，不要贴进回复。CHROME_BIN 可指定 Chromium 路径。
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';

const [target, width = '390', out = '/tmp/airopscat-shot.png'] = process.argv.slice(2);
if (!target) { console.error('用法：node scripts/screenshot.mjs <路径或 URL> [宽度] [输出]'); process.exit(64); }
const url = new URL(target, 'http://127.0.0.1:8080');
const token = readFileSync(join(process.env.AIROPSCAT_LOCAL_DIR || join(homedir(), '.airopscat-local'), 'token'), 'utf8').trim();
const chromeBin = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const profile = mkdtempSync(join(tmpdir(), 'airopscat-shot-'));
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
  const waiters = new Map();
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.method) { waiters.get(message.method)?.(); return; }
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
  await call('Network.enable');
  await call('Network.setCookie', { name: 'airopscat_session', value: token, url: url.origin });
  await call('Emulation.setDeviceMetricsOverride', { width: +width, height: 844, deviceScaleFactor: 2, mobile: +width < 768 });
  await call('Page.enable');
  const loaded = new Promise(resolve => waiters.set('Page.loadEventFired', resolve));
  await call('Page.navigate', { url: url.href });
  await loaded;
  const { result } = await call('Runtime.evaluate', { expression: '({path: location.pathname, scrollWidth: document.documentElement.scrollWidth, height: document.documentElement.scrollHeight})', returnByValue: true });
  const page = result.value;
  if (page.path === '/login') throw new Error('令牌无效或已过期，重新运行 scripts/local-prod.sh');
  const shot = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  writeFileSync(out, Buffer.from(shot.data, 'base64'));
  console.log(`${out} 宽 ${width}，页面 ${page.scrollWidth}x${page.height}`);
  if (page.scrollWidth > +width) { console.error(`横向溢出：scrollWidth ${page.scrollWidth} > ${width}`); process.exitCode = 1; }
} finally {
  ws?.close();
  const exited = new Promise(resolve => chrome.once('exit', resolve));
  chrome.kill();
  await exited;
  rmSync(profile, { recursive: true, force: true });
}
