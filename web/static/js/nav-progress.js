import { Alert, Collapse, Dropdown, Modal, Popover, Tab, Tooltip } from '/static/tabler/js/tabler.esm.min.js';

const progress = document.getElementById('nav-progress');
const positions = new Map();
let entry = 0;
let sequence = 0;
let route = location.pathname + location.search;
let pending = null;
history.replaceState({ ...history.state, airopscatPage: entry }, '');
history.scrollRestoration = 'manual';

function stop() {
  if (pending) pending.abort();
  pending = null;
  progress.classList.add('d-none');
  document.querySelector('.page-wrapper')?.removeAttribute('aria-busy');
}

function nativeRequired() {
  // 运维任务继续使用原生 beforeunload 确认；打开的模态框由整页导航负责释放。
  return 'navigationNative' in document.body.dataset || document.querySelector('.modal-backdrop, .offcanvas-backdrop');
}

function assets(doc) {
  return Array.from(doc.querySelectorAll('script, link[rel="stylesheet"]'), el =>
    el.tagName === 'SCRIPT' ? [el.getAttribute('src'), el.type, el.textContent] : [el.getAttribute('href')]
  );
}
const initialAssets = JSON.stringify(assets(document));

function dispose(root) {
  root.querySelectorAll('.alert, .collapse, .modal, [data-bs-toggle], [data-account-usage]').forEach(el => {
    [Alert, Collapse, Dropdown, Modal, Popover, Tab, Tooltip].forEach(component => component.getInstance(el)?.dispose());
  });
}

function scrollToPage(url, position) {
  if (position) { window.scrollTo({ left: position[0], top: position[1], behavior: 'instant' }); return; }
  if (url.hash) {
    let id;
    try { id = decodeURIComponent(url.hash.slice(1)); } catch (_) { id = url.hash.slice(1); }
    const target = document.getElementById(id) || document.getElementsByName(id)[0];
    if (target) { target.scrollIntoView(); return; }
  }
  window.scrollTo({ left: 0, top: 0, behavior: 'instant' });
}

async function navigate(url, restoreEntry, link) {
  stop();
  // data-loading 链接（如排序列头）把图标换成加载动画；成功时随旧页面一起替换。
  const icon = link?.hasAttribute('data-loading') ? link.querySelector('i') : null;
  const iconClass = icon?.className;
  if (icon) icon.className = 'spinner-border spinner-border-sm';
  const request = new AbortController();
  pending = request;
  progress.classList.remove('d-none');
  document.querySelector('.page-wrapper').setAttribute('aria-busy', 'true');
  const timeout = setTimeout(() => request.abort(), 15000);
  let committed = false;
  try {
    const response = await fetch(url.href, {
      signal: request.signal, headers: { Accept: 'text/html' }, cache: 'no-store'
    });
    if (!response.ok || !response.headers.get('Content-Type')?.includes('text/html') ||
        response.headers.has('Content-Disposition')) throw new Error('需要整页导航');
    const nextURL = new URL(response.url);
    if (nextURL.origin !== location.origin) throw new Error('跨站跳转');
    if (!nextURL.hash) nextURL.hash = url.hash;
    const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
    if (pending !== request) return;
    const nextPage = doc.querySelector('.page-wrapper');
    const nextHeader = doc.querySelector('.page > header');
    if (!nextPage || !nextHeader || JSON.stringify(assets(doc)) !== initialAssets || nativeRequired()) {
      throw new Error('页面需要重新加载');
    }
    const page = document.querySelector('.page-wrapper');
    const header = document.querySelector('.page > header');
    window.AirOpsPage.unmount();
    dispose(page);
    dispose(header);
    // 保留导航栏与进度条节点，仅更新菜单、用户状态和页面内容。
    header.querySelector('.container-xl').replaceWith(nextHeader.querySelector('.container-xl'));
    page.replaceWith(nextPage);
    document.title = doc.title;
    if (restoreEntry === undefined) {
      entry = ++sequence;
      history.pushState({ airopscatPage: entry }, '', nextURL.href);
    } else {
      entry = restoreEntry;
      history.replaceState({ ...history.state, airopscatPage: entry }, '', nextURL.href);
    }
    committed = true;
    route = nextURL.pathname + nextURL.search;
    window.AirOpsPage.mount();
    const heading = nextPage.querySelector('.page-title');
    if (heading) { heading.setAttribute('tabindex', '-1'); heading.focus({ preventScroll: true }); }
    scrollToPage(nextURL, restoreEntry === undefined ? null : positions.get(entry));
  } catch (_) {
    if (pending !== request) return;
    // 不重放 POST；这里接管的只有普通 GET 链接。
    if (committed || restoreEntry !== undefined) location.replace(url.href);
    else location.assign(url.href);
  } finally {
    clearTimeout(timeout);
    if (icon && !committed) icon.className = iconClass;
    if (pending === request) {
      pending = null;
      progress.classList.add('d-none');
      document.querySelector('.page-wrapper')?.removeAttribute('aria-busy');
    }
  }
}

window.addEventListener('scroll', () => { positions.set(entry, [scrollX, scrollY]); }, { passive: true });
document.addEventListener('click', event => {
  const link = event.target.closest('a[href]');
  if (!link || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
  if (link.target || link.hasAttribute('download') || link.hasAttribute('data-bs-toggle') || nativeRequired()) return;
  const url = new URL(link.href, location.href);
  if (url.origin !== location.origin || !/^\/admin(?:\/|$)/.test(url.pathname)) return;
  if (url.pathname + url.search === route && url.hash) { stop(); return; }
  event.preventDefault();
  positions.set(entry, [scrollX, scrollY]);
  navigate(url, undefined, link);
});

window.addEventListener('popstate', event => {
  if (nativeRequired() || typeof event.state?.airopscatPage !== 'number') {
    stop();
    location.reload();
    return;
  }
  if (location.pathname + location.search === route) {
    stop();
    entry = event.state.airopscatPage;
    scrollToPage(new URL(location.href), positions.get(entry));
    return;
  }
  navigate(new URL(location.href), event.state.airopscatPage);
});

// 表单仍走原生提交，避免等待中的 GET 覆盖提交结果。
document.addEventListener('submit', event => { if (!event.defaultPrevented) stop(); });
// 离开文档后交还浏览器恢复滚动（刷新、跨文档后退）；回到本文档再由局部导航接管。
window.addEventListener('pagehide', () => { stop(); history.scrollRestoration = 'auto'; });
window.addEventListener('pageshow', () => { stop(); history.scrollRestoration = 'manual'; });
