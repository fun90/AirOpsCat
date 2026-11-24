// coss-ui.js — 无外部依赖的轻量交互原语（替代 Tabler / Bootstrap JS）
const instances = new WeakMap();
function store(el, Ctor, create) {
  let map = instances.get(el);
  if (!map) { map = new Map(); instances.set(el, map); }
  let inst = map.get(Ctor);
  if (!inst && create) { inst = create(); map.set(Ctor, inst); }
  return inst || null;
}
function unstore(el, Ctor) { instances.get(el)?.delete(Ctor); }

function targetOf(trigger) {
  const sel = trigger.getAttribute('data-bs-target') || trigger.getAttribute('href');
  if (!sel || sel === '#') return null;
  try { return document.querySelector(sel); } catch { return null; }
}

function placeFloating(floating, anchor, { alignEnd = false, gap = 6 } = {}) {
  const rect = anchor.getBoundingClientRect();
  floating.style.position = 'fixed';
  floating.style.zIndex = '1060';
  floating.style.margin = '0';
  floating.style.visibility = 'hidden';
  floating.style.top = '0px';
  floating.style.left = '0px';
  floating.style.right = 'auto';
  floating.style.bottom = 'auto';
  const fw = floating.offsetWidth;
  const fh = floating.offsetHeight;
  const vw = window.visualViewport?.width || window.innerWidth;
  const vh = window.visualViewport?.height || window.innerHeight;

  let top = rect.bottom + gap;
  let originY = 'top';
  if (top + fh > vh - 8 && rect.top - gap - fh >= 8) {
    top = rect.top - gap - fh;
    originY = 'bottom';
  }
  if (top < 8) top = 8;
  let left = alignEnd ? rect.right - fw : rect.left;
  if (left + fw > vw - 8) left = vw - fw - 8;
  if (left < 8) left = 8;
  const originX = alignEnd ? 'right' : 'left';

  floating.style.top = `${Math.round(top)}px`;
  floating.style.left = `${Math.round(left)}px`;
  floating.style.transformOrigin = `${originY} ${originX}`;
  floating.style.visibility = '';
}

export class Dropdown {
  constructor(trigger) {
    this.trigger = trigger;
    this.menu = trigger.closest('.dropdown, .dropup, .btn-group, .nav-item')?.querySelector(':scope > .dropdown-menu')
      || trigger.nextElementSibling;
    store(trigger, Dropdown, () => this);
  }
  static getInstance(el) { return store(el, Dropdown); }
  static getOrCreateInstance(el) { return store(el, Dropdown, () => new Dropdown(el)); }
  isOpen() { return Boolean(this.menu?.classList.contains('show')); }
  show() {
    if (!this.menu || this.isOpen()) return;
    document.querySelectorAll('.dropdown-menu.show').forEach(m => {
      if (m === this.menu) return;
      const t = m.parentElement?.querySelector('[data-bs-toggle="dropdown"]');
      if (t) Dropdown.getInstance(t)?.hide();
      else m.classList.remove('show');
    });
    this.menu.classList.add('show');
    this.trigger.setAttribute('aria-expanded', 'true');
    const inMobileNav = window.innerWidth < 768 && Boolean(this.trigger.closest('.navbar-collapse'));
    const fixed = !inMobileNav && (this.trigger.hasAttribute('data-bs-popper-config') || Boolean(this.trigger.closest('.table-responsive, .card, .list-group')));
    if (fixed) {
      placeFloating(this.menu, this.trigger, { alignEnd: this.menu.classList.contains('dropdown-menu-end') });
    }
  }
  hide() {
    if (!this.menu) return;
    this.menu.classList.remove('show');
    this.menu.style.position = '';
    this.menu.style.top = '';
    this.menu.style.left = '';
    this.menu.style.right = '';
    this.menu.style.bottom = '';
    this.menu.style.zIndex = '';
    this.menu.style.margin = '';
    this.menu.style.transformOrigin = '';
    this.trigger.setAttribute('aria-expanded', 'false');
  }
  toggle() { this.isOpen() ? this.hide() : this.show(); }
  dispose() { this.hide(); unstore(this.trigger, Dropdown); }
}

let openModals = [];
let backdrop = null;
function syncBackdrop() {
  if (openModals.length > 0) {
    if (!backdrop) {
      backdrop = document.createElement('div');
      backdrop.className = 'modal-backdrop fade show';
      document.body.appendChild(backdrop);
    }
    document.documentElement.style.overflow = 'hidden';
    document.body.style.overflow = 'hidden';
  } else {
    backdrop?.remove();
    backdrop = null;
    document.documentElement.style.overflow = '';
    document.body.style.overflow = '';
  }
}

export class Modal {
  constructor(el) {
    this.el = el;
    store(el, Modal, () => this);
  }
  static getInstance(el) { return store(el, Modal); }
  static getOrCreateInstance(el) { return store(el, Modal, () => new Modal(el)); }
  show(relatedTarget) {
    if (this.el.classList.contains('show')) return;
    const showEvent = new CustomEvent('show.bs.modal', { bubbles: true, cancelable: true });
    showEvent.relatedTarget = relatedTarget;
    if (!this.el.dispatchEvent(showEvent)) return;
    this._prevFocus = relatedTarget || (document.activeElement instanceof HTMLElement ? document.activeElement : null);
    openModals = openModals.filter(m => m !== this);
    openModals.push(this);
    this.el.style.display = 'block';
    this.el.removeAttribute('aria-hidden');
    this.el.setAttribute('aria-modal', 'true');
    if (!this.el.hasAttribute('role')) this.el.setAttribute('role', 'dialog');
    this.el.classList.add('show');
    syncBackdrop();
    this.el.focus({ preventScroll: true });
    const shownEvent = new CustomEvent('shown.bs.modal', { bubbles: true });
    shownEvent.relatedTarget = relatedTarget;
    this.el.dispatchEvent(shownEvent);
  }
  hide() {
    if (!this.el.classList.contains('show') && this.el.style.display !== 'block') return;
    this.el.classList.remove('show');
    this.el.style.display = 'none';
    this.el.setAttribute('aria-hidden', 'true');
    this.el.removeAttribute('aria-modal');
    openModals = openModals.filter(m => m !== this);
    syncBackdrop();
    this.el.dispatchEvent(new CustomEvent('hidden.bs.modal', { bubbles: true }));
    if (this._prevFocus?.isConnected) this._prevFocus.focus({ preventScroll: true });
    this._prevFocus = null;
  }
  dispose() { this.hide(); unstore(this.el, Modal); }
}

export class Tab {
  constructor(trigger) {
    this.trigger = trigger;
    store(trigger, Tab, () => this);
  }
  static getInstance(el) { return store(el, Tab); }
  static getOrCreateInstance(el) { return store(el, Tab, () => new Tab(el)); }
  show() {
    const list = this.trigger.closest('[role="tablist"], .nav');
    list?.querySelectorAll('[data-bs-toggle="tab"]').forEach(btn => {
      btn.classList.remove('active');
      btn.setAttribute('aria-selected', 'false');
      const pane = targetOf(btn);
      if (pane) pane.classList.remove('active', 'show');
    });
    this.trigger.classList.add('active');
    this.trigger.setAttribute('aria-selected', 'true');
    const pane = targetOf(this.trigger);
    if (pane) pane.classList.add('active', 'show');
    this.trigger.dispatchEvent(new CustomEvent('shown.bs.tab', { bubbles: true }));
  }
  dispose() { unstore(this.trigger, Tab); }
}

export class Collapse {
  constructor(el) {
    this.el = el;
    store(el, Collapse, () => this);
  }
  static getInstance(el) { return store(el, Collapse); }
  static getOrCreateInstance(el) { return store(el, Collapse, () => new Collapse(el)); }
  _syncTriggers(open) {
    if (!this.el.id) return;
    document.querySelectorAll(`[data-bs-toggle="collapse"][data-bs-target="#${CSS.escape(this.el.id)}"], [data-bs-toggle="collapse"][href="#${CSS.escape(this.el.id)}"]`)
      .forEach(t => t.setAttribute('aria-expanded', open ? 'true' : 'false'));
  }
  show() { this.el.classList.add('show'); this._syncTriggers(true); }
  hide() { this.el.classList.remove('show'); this._syncTriggers(false); }
  toggle() { this.el.classList.contains('show') ? this.hide() : this.show(); }
  dispose() { unstore(this.el, Collapse); }
}

let popSeq = 0;
export class Popover {
  constructor(trigger, opts = {}) {
    this.trigger = trigger;
    this.opts = opts;
    this.tip = null;
    store(trigger, Popover, () => this);
  }
  static getInstance(el) { return store(el, Popover); }
  static getOrCreateInstance(el, opts) { return store(el, Popover, () => new Popover(el, opts)); }
  show() {
    if (!this.tip) {
      const id = `coss-popover-${++popSeq}`;
      const el = document.createElement('div');
      el.className = 'popover fade show';
      el.id = id;
      el.setAttribute('role', 'tooltip');
      if (this.opts.title) {
        const h = document.createElement('div');
        h.className = 'popover-header';
        h.textContent = this.opts.title;
        el.appendChild(h);
      }
      const b = document.createElement('div');
      b.className = 'popover-body';
      if (this.opts.html) b.innerHTML = this.opts.content || '';
      else b.textContent = this.opts.content || '';
      el.appendChild(b);
      document.body.appendChild(el);
      this.tip = el;
      this.trigger.setAttribute('aria-describedby', id);
    }
    this.update();
  }
  update() {
    if (!this.tip || !this.trigger.isConnected) return;
    placeFloating(this.tip, this.trigger, { gap: 8 });
  }
  hide() {
    this.tip?.remove();
    this.tip = null;
    this.trigger.removeAttribute('aria-describedby');
  }
  dispose() { this.hide(); unstore(this.trigger, Popover); }
}

export class Alert {
  constructor(el) { this.el = el; store(el, Alert, () => this); }
  static getInstance(el) { return store(el, Alert); }
  static getOrCreateInstance(el) { return store(el, Alert, () => new Alert(el)); }
  close() { this.el.remove(); }
  dispose() { unstore(this.el, Alert); }
}

export class Tooltip {
  constructor(el) { this.el = el; store(el, Tooltip, () => this); }
  static getInstance(el) { return store(el, Tooltip); }
  static getOrCreateInstance(el) { return store(el, Tooltip, () => new Tooltip(el)); }
  dispose() { unstore(this.el, Tooltip); }
}

function closeDropdowns() {
  document.querySelectorAll('.dropdown-menu.show').forEach(m => {
    const t = m.parentElement?.querySelector('[data-bs-toggle="dropdown"]');
    if (t) Dropdown.getInstance(t)?.hide();
    else m.classList.remove('show');
  });
}

// 全局事件委托：下拉菜单、弹窗、页签、折叠、提示关闭
document.addEventListener('click', event => {
  const dismissModal = event.target.closest('[data-bs-dismiss="modal"]');
  if (dismissModal) {
    const m = dismissModal.closest('.modal');
    if (m) Modal.getOrCreateInstance(m).hide();
    return;
  }
  const dismissAlert = event.target.closest('[data-bs-dismiss="alert"]');
  if (dismissAlert) {
    const a = dismissAlert.closest('.alert');
    if (a) Alert.getOrCreateInstance(a).close();
    return;
  }
  const ddTrigger = event.target.closest('[data-bs-toggle="dropdown"]');
  if (ddTrigger) {
    event.preventDefault();
    Dropdown.getOrCreateInstance(ddTrigger).toggle();
    return;
  }
  const modalTrigger = event.target.closest('[data-bs-toggle="modal"]');
  if (modalTrigger) {
    event.preventDefault();
    closeDropdowns();
    const target = targetOf(modalTrigger);
    if (target) Modal.getOrCreateInstance(target).show(modalTrigger);
    return;
  }
  const tabTrigger = event.target.closest('[data-bs-toggle="tab"]');
  if (tabTrigger) {
    event.preventDefault();
    Tab.getOrCreateInstance(tabTrigger).show();
    return;
  }
  const collapseTrigger = event.target.closest('[data-bs-toggle="collapse"]');
  if (collapseTrigger) {
    event.preventDefault();
    const target = targetOf(collapseTrigger);
    if (target) Collapse.getOrCreateInstance(target).toggle();
    return;
  }
  // 点击弹窗遮罩空白处关闭
  if (event.target.classList?.contains('modal') && event.target.classList.contains('show')) {
    Modal.getOrCreateInstance(event.target).hide();
    return;
  }
  // 点击外部或菜单项时收起已打开的下拉菜单
  const inMenu = event.target.closest('.dropdown-menu');
  if (!inMenu || event.target.closest('.dropdown-item')) {
    closeDropdowns();
  }
});

document.addEventListener('keydown', event => {
  if (event.key !== 'Escape') return;
  if (openModals.length > 0) {
    openModals[openModals.length - 1].hide();
    return;
  }
  closeDropdowns();
});
