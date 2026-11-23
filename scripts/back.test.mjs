import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../web/static/js/back.js', import.meta.url), 'utf8');

// 在最小 DOM 替身里运行 back.js，返回被改写的链接与表单
function run({ path, referrer = '', stored = {}, links = [], forms = [] }) {
  const origin = 'http://localhost';
  const store = { ...stored };
  const a = links.map(l => ({ ...l, href: l.href, getAttribute() { return l.href; }, hasAttribute: n => n === 'data-back' && !!l.back }));
  const f = forms.map(action => ({ action: origin + action, appended: [], appendChild(i) { this.appended.push(i); } }));
  const ctx = {
    URL,
    location: { pathname: path.split('?')[0], search: path.includes('?') ? '?' + path.split('?')[1] : '', href: origin + path, origin },
    sessionStorage: { getItem: k => store[k] ?? null, setItem: (k, v) => { store[k] = v; } },
    document: {
      referrer,
      querySelectorAll: s => (s.startsWith('a') ? a : f),
      createElement: () => ({}),
    },
    window: { AirOpsPage: { register: fn => fn() } },
  };
  vm.runInNewContext(source, ctx);
  return { store, links: a, forms: f };
}

test('列表页记住分页与筛选', () => {
  const r = run({ path: '/admin/domains?page=2&q=a' });
  assert.equal(r.store['back:/admin/domains'], '/admin/domains?page=2&q=a');
});

test('返回与取消回到来源页，并去掉提示参数', () => {
  const r = run({
    path: '/admin/domains/1/records/new',
    referrer: 'http://localhost/admin/dashboard?msg=x',
    links: [{ href: '/admin/domains/1/records', back: true }, { href: '/admin/domains' }],
    forms: ['/admin/domains/1/records'],
  });
  assert.equal(r.links[0].href, '/admin/dashboard');
  assert.equal(r.links[1].href, '/admin/dashboard');
  assert.equal(r.forms[0].appended[0].value, '/admin/dashboard');
});

test('无来源时回退到最近访问的列表页', () => {
  const r = run({
    path: '/admin/domains/new',
    stored: { 'back:/admin/domains': '/admin/domains?page=3' },
    links: [{ href: '/admin/domains' }],
    forms: ['/admin/domains'],
  });
  assert.equal(r.links[0].href, '/admin/domains?page=3');
  assert.equal(r.forms[0].appended[0].value, '/admin/domains?page=3');
});

test('跨域来源忽略；页内取消链接（非列表页）保持原样', () => {
  const r = run({
    path: '/admin/accounts/7/bindings',
    referrer: 'http://evil.example/admin/x',
    links: [{ href: '/admin/accounts/7/bindings?from=edit' }],
  });
  assert.equal(r.links[0].href, '/admin/accounts/7/bindings?from=edit');
});

test('局部导航来源优先于陈旧的 document.referrer，且支持概览页 /admin', () => {
  const origin = 'http://localhost';
  const a = [{ href: '/admin/nodes', getAttribute() { return '/admin/nodes'; }, hasAttribute: n => n === 'data-back' }];
  const f = [{ action: origin + '/admin/nodes/5', appended: [], appendChild(i) { this.appended.push(i); } }];
  const ctx = {
    URL,
    location: { pathname: '/admin/nodes/5/edit', search: '', href: origin + '/admin/nodes/5/edit', origin },
    sessionStorage: { getItem: () => null, setItem: () => {} },
    document: { referrer: origin + '/admin/stale', querySelectorAll: s => (s.startsWith('a') ? a : f), createElement: () => ({}) },
    window: { __airopscatReferrer: origin + '/admin?days=3', AirOpsPage: { register: fn => fn() } },
  };
  vm.runInNewContext(source, ctx);
  assert.equal(a[0].href, '/admin?days=3');
  assert.equal(f[0].appended[0].value, '/admin?days=3');
});
