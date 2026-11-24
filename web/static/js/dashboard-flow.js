// 概览页「运行情况」两级交互拓扑图：
// 1) 全球地图：账户国家 → 服务器国家动态弧线，点击服务器国家进入下钻视图；
// 2) 服务器国家下钻：账户国家 → 部署位 → 服务器 三列动态流图（宽容器）与流向卡片（窄容器）。
(() => {
  const SVG_NS = 'http://www.w3.org/2000/svg';

  // Natural Earth 110m 离线采样点阵位图（180×79，lon: -180..180, lat: -58..82 -> 1000×440）
  const WORLD_DOTS_COLS = 180;
  const WORLD_DOTS_ROWS = 79;
  const WORLD_DOTS_B64 = 'AAAAAADo//z//wcAAAAAAAACAAAAAAAAAAAAgP/g//8fAPgAAAAA4AAAAAAAAAAAAADEh////wAAJgAAAABAAAAAAAAAAACwGQEA//8PAAAAAHgA8H8AOAAAAAAAcAAIAMD/fwAAAABgAOD/AQAAAAAAAID7ef4A/P8DAAAAAAPr//8/8AAAADwAAH7M/4D/HwAAwAMAuP//////AwD4//9/gBsP8P8BAMD/AXj+////////D///////4YP/AQAA/n/9v////////8/8/////4sP8AN4APB5/P//////////EP7///+fwAMfAADA7////////////wf4////f4AD4AEAAH7+/////////18fAN74//8HeAIAAADgB/////////9/GACAAfj//4B/AAAACDD8////////D2AAAAQA//8/8AcAAIBB4////////38ADwAAAOD//9//AQAANET/////////A3AAAAAA/v//+T8AAGD3//////////8DAQAAAMD/////AwAAsP//////////LwAAAAAA+P///2AAAAD+//////////8CAAAAAAD///8/DAAA4P//////////DwAAAAAA8P///w8AAAD+/unj/////38AAAAAAAD///8nAAAA4MsPvP//////YwAAAAAA8P//fwAAAOBP8YDz/////wcDAAAAAAD///8BAAAAPCTzf/////87EAAAAAAA8P//DwAAAOADsP/j////HwIBAAAAAAD+//8AAAAAGC/w//////9nDAAAAAAAwP//DwAAAIB/QPT/////P/IAAAAAAAD4/z8AAAAA/A8A//////+DAQAAAAAAAP//AQAAAOD/c/r/////fwgAAAAAAADQ/xkAAAAA/v//v/////8HAAAAAAAAAP4BAgAAAPD///fz////PwAAAAAAAACAHyAAAACA//9/f/j///8DAAAAAAAAAPQBAAAAAPz//+8v8P//XwAAAAAAAAAAHjAAAADA/////A/+938EAAAAAAAAAOBhCAAAAPz//89/4D9/AAAAAAAAAAAAPAMEAADA////+Qf84BcAAAAAAAAAAIA/AAAAAPz//58/gAd+QAAAAAAAAAAAgA8AAADA////+wA4gA8EAAAAAAAAAADgAAAAAPz//38DgAP4wQAAAAAAAAAAAAgAAADA////jwAwgB4QAAAAAAAAAACA8A0AAPj///8HAAPIoAAAAAAAAAAAALD/AACA////fwBAgAAQAAAAAAAAAAAA+B8AAPD///8HAAQQEAEAAAAAAAAAAID/DwAABv7/PwAAQIMBAAAAAAAAAAAA+P8BAACA//8BAAAoGAAAAAAAAAAAAID/HwAAAPj/DwAAgOERAAAAAAAAAAAA/P8DAACA/38AAAAwHgAAAAAAAAAAAMD//wAAAPj/AwAAAOMEDQAAAAAAAAAA/P9/AAAA/z8AAABgCPADAAAAAAAAAMD//w8AAPD/AQAAAAQAeAsAAAAAAAAA/P//AQAA/z8AAAAAA4APAAAAAAAAAID//w8AAPD/AwAAAAAQgAEAAAAAAAAA+P9/AAAA/j8AAAAAAAAAAAAAAAAAAAD//wcAAOD/QwAAAACAIwAAAAAAAAAA8P9/AAAA/z8EAAAAADoCAAAAAAAAAAD+/wMAAPD/cwAAAADwZwAAAAAAAAAAgP8/AAAA/w8HAAAAgP8HAAAAAAAAAAD4/wMAAPB/MAAAAAD8/wAAAAAAAAAAgP8/AAAA/g8DAAAA8P8fAAAAAAAAAAD4/wAAAOD/MAAAAID//wEAAAAAAAAAgP8DAAAA/gcBAAAA+P8/AAAAAAAAAAD4PwAAAMA/AAAAAID//wcAAAAAAAAAwP8DAAAA/AMAAAAA+P9/AAAAAAAAAAD8HwAAAIAfAAAAAAD//wcAAAAAAAAAwP8AAAAA+AAAAAAA8OE/AAAAAAAAAAD8BwAAAIABAAAAAAAD+AMAAAAAAAAAwB8AAAAAAAAAAAAAAAAfAAAAAAAAAAD+AQAAAAAAAAAAAAAA8AEgAAAAAAAA4AcAAAAAAAAAAAAAAAAAAAIAAAAAAAAeAAAAAAAAAAAAAAAAwAAwAAAAAAAAwAMAAAAAAAAAAAAAAAAIgAEAAAAAAAAeAAAAAAAAAAAAAAAAAAAMAAAAAAAA8AAAAAAAAAAAAAAAAAAAQAAAAAAAAAAfAAAAAAAAAAAAAAAAAAAAAAAAAAAA8AAAAAAAAAAAAAAAAAAAAAAAAAAAAACPAQAAAAAAAAAAAAAAAAAAAAAAAAAA4AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=';

  let cachedDotPath = '';
  function worldDotsPath() {
    if (cachedDotPath) return cachedDotPath;
    const bin = atob(WORLD_DOTS_B64);
    const parts = [];
    for (let r = 0; r < WORLD_DOTS_ROWS; r++) {
      const y = (((r + 0.5) / WORLD_DOTS_ROWS) * 440).toFixed(1);
      for (let c = 0; c < WORLD_DOTS_COLS; c++) {
        const idx = r * WORLD_DOTS_COLS + c;
        if (bin.charCodeAt(idx >> 3) & (1 << (idx & 7))) {
          const x = (((c + 0.5) / WORLD_DOTS_COLS) * 1000).toFixed(1);
          parts.push(`M${x},${y}h0.01`);
        }
      }
    }
    cachedDotPath = parts.join('');
    return cachedDotPath;
  }

  function project(lon, lat) {
    const x = ((lon + 180) / 360) * 1000;
    const clampedLat = Math.max(-58, Math.min(82, lat));
    const y = ((82 - clampedLat) / 140) * 440;
    return [x, y];
  }

  function svgEl(tag, attrs) {
    const el = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) {
      el.setAttribute(k, String(v));
    }
    return el;
  }

  function esc(str) {
    return String(str ?? '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function metricIcon(id) {
    return `<svg class="coss-icon" aria-hidden="true"><use href="/static/icons/lucide.svg?v=3#${id}"></use></svg>`;
  }

  function truncText(str, max) {
    const segs = typeof Intl !== 'undefined' && Intl.Segmenter
      ? Array.from(new Intl.Segmenter('zh', { granularity: 'grapheme' }).segment(str), s => s.segment)
      : Array.from(str);
    return segs.length > max ? segs.slice(0, max - 1).join('') + '…' : str;
  }

  function init() {
    const root = document.querySelector('[data-dash-flow]');
    if (!root) return;

    let topo;
    try {
      topo = JSON.parse(root.dataset.topology || '{}');
    } catch (_) {
      return;
    }
    if (!topo || !Array.isArray(topo.serverCountries) || topo.serverCountries.length === 0) return;

    const mapView = root.querySelector('[data-dash-map-view]');
    const mapSvg = root.querySelector('[data-dash-map-svg]');
    const drillView = root.querySelector('[data-dash-drill-view]');
    const drillMeta = root.querySelector('[data-dash-drill-meta]');
    const drillSwitcher = root.querySelector('[data-dash-drill-switcher]');
    const drillCanvas = root.querySelector('[data-dash-drill-canvas]');
    const drillSvg = root.querySelector('[data-dash-drill-svg]');
    const drillStage = root.querySelector('[data-dash-drill-stage]');
    const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

    const countryMap = new Map();
    for (const c of topo.clientCountries || []) countryMap.set(c.code, c);
    for (const c of topo.serverCountries || []) countryMap.set(c.code, c);

    let currentCountry = null;
    let mapFocus = null;
    let drillHover = null;
    let drillLockClient = null;
    let mobileSelect = null; // { type: 'client'|'placement'|'server', id }
    let rafId = 0;

    // 1) 渲染全球点阵地图（根据实际连接数据的包围盒自动缩放并居中显示）
    function renderWorldMap() {
      const frag = document.createDocumentFragment();
      const flows = topo.countryFlows || [];

      // 预计算所有连线几何与活跃点包围盒（含弧线顶点，防止放大后弧顶被裁）
      let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
      const includePt = (x, y) => {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      };

      const flowGeom = [];
      for (const fl of flows) {
        const src = countryMap.get(fl.from);
        const dst = countryMap.get(fl.to);
        if (!src || !dst) continue;
        const [x1, y1] = project(src.lon, src.lat);
        const [x2, y2] = project(dst.lon, dst.lat);
        includePt(x1, y1);
        includePt(x2, y2);
        let d;
        if (fl.from === fl.to) {
          d = `M ${x1.toFixed(1)} ${y1.toFixed(1)} C ${(x1 - 24).toFixed(1)} ${(y1 - 36).toFixed(1)}, ${(x1 + 24).toFixed(1)} ${(y1 - 36).toFixed(1)}, ${x1.toFixed(1)} ${y1.toFixed(1)}`;
          includePt(x1, Math.max(8, y1 - 30));
        } else {
          const mx = (x1 + x2) / 2;
          const my = (y1 + y2) / 2;
          const dist = Math.hypot(x2 - x1, y2 - y1);
          const lift = Math.min(115, Math.max(22, dist * 0.22));
          const cy = Math.max(12, my - lift);
          d = `M ${x1.toFixed(1)} ${y1.toFixed(1)} Q ${mx.toFixed(1)} ${cy.toFixed(1)} ${x2.toFixed(1)} ${y2.toFixed(1)}`;
          // 二次贝塞尔中点 y = 0.25*y1 + 0.5*cy + 0.25*y2
          includePt(mx, 0.25 * y1 + 0.5 * cy + 0.25 * y2);
        }
        flowGeom.push({ fl, d });
      }
      if (!Number.isFinite(minX)) {
        for (const c of topo.serverCountries || []) {
          const [x, y] = project(c.lon, c.lat);
          includePt(x, y);
        }
      }

      // 计算自适应居中 viewBox（保持 1000:440 比例，留出标签与呼吸边距，限制最大放大倍数）
      let vx = 0, vy = 0, vw = 1000, vh = 440;
      if (Number.isFinite(minX)) {
        const aspect = 1000 / 440;
        const spanX = Math.max(60, maxX - minX);
        const spanY = Math.max(40, maxY - minY);
        const padX = Math.max(68, spanX * 0.14);
        const padY = Math.max(46, spanY * 0.2);
        let targetW = spanX + padX * 2;
        let targetH = spanY + padY * 2;
        if (targetW / targetH > aspect) targetH = targetW / aspect;
        else targetW = targetH * aspect;
        // 最小宽度 380（约 2.6x 最大放大），最大宽度 1000
        vw = Math.max(380, Math.min(1000, targetW));
        vh = vw / aspect;
        const cx = (minX + maxX) / 2;
        const cy = (minY + maxY) / 2;
        vx = Math.max(0, Math.min(1000 - vw, cx - vw / 2));
        vy = Math.max(0, Math.min(440 - vh, cy - vh / 2));
      }
      mapSvg.setAttribute('viewBox', `${vx.toFixed(1)} ${vy.toFixed(1)} ${vw.toFixed(1)} ${vh.toFixed(1)}`);

      // 反向缩放因子，保证放大地图时节点圆点、标签字体与点阵尺寸保持精致统一
      const inv = vw / 1000;
      const dotStroke = Math.max(1.5, 2.6 * Math.pow(inv, 0.72));

      // 高精度点阵陆地（单 path + round stroke-linecap 渲染 4300+ 像素圆点）
      frag.appendChild(svgEl('path', {
        class: 'dash-map-dots',
        d: worldDotsPath(),
        style: `stroke-width:${dotStroke.toFixed(2)}px`
      }));

      // 账户国家 → 服务器国家动态弧线
      const edgesG = svgEl('g', {});
      flowGeom.forEach(({ fl, d }, idx) => {
        const w = Math.max(1, Math.min(4, Number(fl.weight) || 1));
        const g = svgEl('g', {
          class: `dash-flow-edge dash-flow-w${w}`,
          'data-map-from': fl.from,
          'data-map-to': fl.to
        });
        g.appendChild(svgEl('path', { class: 'dash-map-arc-track', d }));
        g.appendChild(svgEl('path', { class: 'dash-map-arc-stream', d }));
        if (!reducedMotion) {
          const dur = (2.8 - w * 0.3).toFixed(2) + 's';
          const begin = `-${((idx * 0.55) % 2.4).toFixed(2)}s`;
          const dot = svgEl('circle', { class: 'dash-flow-pulse', r: ((1.5 + w * 0.18) * inv).toFixed(2) });
          dot.appendChild(svgEl('animateMotion', { dur, begin, repeatCount: 'indefinite', path: d }));
          g.appendChild(dot);
        }
        edgesG.appendChild(g);
      });
      frag.appendChild(edgesG);

      // 绘制源国家点（带标签防重叠偏移，尺寸随 viewBox 反向微调）
      const dstSet = new Set((topo.serverCountries || []).map(c => c.code));
      const nodesG = svgEl('g', {});
      const placedLabels = [];
      const srcFont = (10.5 * inv).toFixed(2);
      const dstFont = (11 * inv).toFixed(2);

      for (const c of topo.clientCountries || []) {
        if (dstSet.has(c.code)) continue;
        const [x, y] = project(c.lon, c.lat);
        const g = svgEl('g', { class: 'dash-map-node', 'data-map-src': c.code });
        const title = svgEl('title', {});
        title.textContent = `来源：${c.name} · ${c.accounts} 账户 · ${c.connections} 连接`;
        g.appendChild(title);
        g.appendChild(svgEl('circle', { class: 'dash-map-ring-src', cx: x.toFixed(1), cy: y.toFixed(1), r: (7.5 * inv).toFixed(2) }));
        g.appendChild(svgEl('circle', { class: 'dash-map-dot-src', cx: x.toFixed(1), cy: y.toFixed(1), r: (3.6 * inv).toFixed(2), style: `stroke-width:${(1.6 * inv).toFixed(2)}px` }));
        const labelText = `${c.name} ${c.accounts}`;
        const boxW = Math.max(40, labelText.length * 7.8 + 8) * inv;
        let lx = x;
        let ly = y + 17 * inv;
        for (const prev of placedLabels) {
          if (Math.abs(lx - prev.x) < (boxW + prev.w) * 0.48 && Math.abs(ly - prev.y) < 16 * inv) {
            ly = prev.y < y ? y + 18 * inv : prev.y + 15 * inv;
          }
        }
        lx = Math.max(vx + boxW / 2 + 6 * inv, Math.min(vx + vw - boxW / 2 - 6 * inv, lx));
        ly = Math.max(vy + 14 * inv, Math.min(vy + vh - 8 * inv, ly));
        placedLabels.push({ x: lx, y: ly, w: boxW });
        const lbl = svgEl('text', {
          class: 'dash-map-label-src',
          x: lx.toFixed(1),
          y: ly.toFixed(1),
          'text-anchor': 'middle',
          style: `font-size:${srcFont}px`
        });
        lbl.textContent = labelText;
        g.appendChild(lbl);
        nodesG.appendChild(g);
      }

      // 服务器国家（可点击进入下钻视图）
      for (const c of topo.serverCountries || []) {
        const [x, y] = project(c.lon, c.lat);
        const g = svgEl('g', {
          class: 'dash-map-node dash-map-node-dst',
          'data-map-dst': c.code,
          role: 'button',
          tabindex: '0',
          'aria-label': `查看 ${c.name} 部署位详情（${c.accounts} 账户，${c.connections} 连接）`
        });
        const title = svgEl('title', {});
        title.textContent = `${c.name}（点击进入）：${c.placementCount} 部署位 · ${c.serverCount} 服务器 · ${c.accounts} 账户 · ${c.connections} 连接`;
        g.appendChild(title);
        g.appendChild(svgEl('circle', { class: 'dash-map-ring-dst', cx: x.toFixed(1), cy: y.toFixed(1), r: (10.5 * inv).toFixed(2), style: `stroke-width:${(1.1 * inv).toFixed(2)}px` }));
        g.appendChild(svgEl('circle', { class: 'dash-map-dot-dst', cx: x.toFixed(1), cy: y.toFixed(1), r: (5.2 * inv).toFixed(2), style: `stroke-width:${(2 * inv).toFixed(2)}px` }));

        const labelText = `${c.name} · ${c.accounts}`;
        const boxW = Math.max(48, labelText.length * 8.2 + 14) * inv;
        const boxH = 19 * inv;
        let lx = x;
        let ly = y - 16 * inv;
        if (ly < vy + 18 * inv) ly = y + 24 * inv;
        for (const prev of placedLabels) {
          if (Math.abs(lx - prev.x) < (boxW + prev.w) * 0.45 && Math.abs(ly - prev.y) < 20 * inv) {
            ly = prev.y < y ? y + 24 * inv : prev.y - 22 * inv;
          }
        }
        lx = Math.max(vx + boxW / 2 + 6 * inv, Math.min(vx + vw - boxW / 2 - 6 * inv, lx));
        ly = Math.max(vy + 16 * inv, Math.min(vy + vh - 12 * inv, ly));
        placedLabels.push({ x: lx, y: ly, w: boxW });

        g.appendChild(svgEl('rect', {
          class: 'dash-map-label-bg',
          x: (lx - boxW / 2).toFixed(1),
          y: (ly - 11.5 * inv).toFixed(1),
          width: boxW.toFixed(1),
          height: boxH.toFixed(1),
          rx: (9.5 * inv).toFixed(1),
          style: `stroke-width:${(0.9 * inv).toFixed(2)}px`
        }));
        const lbl = svgEl('text', {
          class: 'dash-map-label',
          x: lx.toFixed(1),
          y: (ly + 1.5 * inv).toFixed(1),
          'text-anchor': 'middle',
          style: `font-size:${dstFont}px`
        });
        lbl.textContent = labelText;
        g.appendChild(lbl);
        nodesG.appendChild(g);
      }

      frag.appendChild(nodesG);
      mapSvg.replaceChildren(frag);
    }

    function applyMapFocus() {
      const hasFocus = mapFocus !== null;
      mapSvg.classList.toggle('has-focus', hasFocus);
      const activeSrc = new Set();
      const activeDst = new Set();
      if (hasFocus) {
        for (const fl of topo.countryFlows || []) {
          if ((mapFocus.type === 'src' && fl.from === mapFocus.code) ||
              (mapFocus.type === 'dst' && fl.to === mapFocus.code)) {
            activeSrc.add(fl.from);
            activeDst.add(fl.to);
          }
        }
        if (mapFocus.type === 'src') activeSrc.add(mapFocus.code);
        if (mapFocus.type === 'dst') activeDst.add(mapFocus.code);
      }
      mapSvg.querySelectorAll('[data-map-src]').forEach(el => el.classList.toggle('is-active', activeSrc.has(el.dataset.mapSrc)));
      mapSvg.querySelectorAll('[data-map-dst]').forEach(el => el.classList.toggle('is-active', activeDst.has(el.dataset.mapDst)));
      mapSvg.querySelectorAll('[data-map-from]').forEach(el => {
        const hit = hasFocus && (
          (mapFocus.type === 'src' && el.dataset.mapFrom === mapFocus.code) ||
          (mapFocus.type === 'dst' && el.dataset.mapTo === mapFocus.code)
        );
        el.classList.toggle('is-active', hit);
      });
    }

    // 2) 进入 / 退出服务器国家下钻视图
    function openCountry(code) {
      const detail = topo.details?.[code];
      if (!detail) return;
      currentCountry = code;
      drillHover = null;
      drillLockClient = null;
      mobileSelect = detail.placements.length > 0 ? { type: 'placement', id: detail.placements[0].key } : null;
      mapView.hidden = true;
      drillView.hidden = false;

      drillMeta.textContent = `${detail.placements.length} 部署位 · ${detail.servers.length} 服务器 · ${detail.country.accounts} 账户`;
      drillSwitcher.querySelectorAll('[data-switch-country]').forEach(btn => {
        const active = btn.dataset.switchCountry === code;
        btn.classList.toggle('active', active);
        btn.setAttribute('aria-selected', active ? 'true' : 'false');
      });

      // 桌面端：从上到下三层光流拓扑（顶层来源胶囊 → 中层部署位矩阵 → 底层服务器枢纽）
      const clientsHTML = detail.clientCountries.map(c => `
        <button type="button" class="dash-pill-client" data-drill-client="${esc(c.code)}">
          <span class="dash-pill-dot" aria-hidden="true"></span>
          <span class="dash-pill-name">${esc(c.name)}</span>
          <span class="dash-flow-metrics">
            <span class="dash-flow-metric" title="${c.accounts} 在线账户">${metricIcon('users')}${c.accounts}</span>
            <span class="dash-flow-metric" title="${c.connections} 连接">${metricIcon('plug-connected')}${c.connections}</span>
          </span>
          <span class="dash-flow-port-dot dash-flow-dot-src" aria-hidden="true"></span>
        </button>`).join('');

      const placementsHTML = detail.placements.map(p => {
        const hostText = p.domain || 'IP 直连';
        const hostTitle = p.domainTitle || 'IP 直连';
        const portHTML = p.port ? `<span class="dash-flow-port font-monospace">:${p.port}</span>` : '';
        const connText = p.connKnown ? String(p.connections) : '—';
        return `
        <div class="dash-card-placement" data-drill-placement="${esc(p.key)}">
          <a class="dash-flow-node-main text-reset text-decoration-none" href="${esc(p.editUrl)}">
            <div class="dash-flow-item-top">
              <span class="dash-flow-title text-truncate" title="${esc(p.subName)}">${esc(p.subName)}</span>
              <span class="dash-flow-metrics">
                <span class="dash-flow-metric" title="${p.accounts} 在线账户">${metricIcon('users')}${p.accounts}</span>
                <span class="dash-flow-metric" title="${p.connKnown ? `${p.connections} 连接` : '连接数未知'}">${metricIcon('plug-connected')}${connText}</span>
              </span>
            </div>
            <div class="dash-flow-item-sub">
              <span class="dash-flow-host font-monospace text-truncate" title="${esc(hostTitle)}">${esc(hostText)}</span>
              ${portHTML}
            </div>
          </a>
          <span class="dash-flow-port-dot dash-flow-dot-in" aria-hidden="true"></span>
          <span class="dash-flow-port-dot dash-flow-dot-out" aria-hidden="true"></span>
        </div>`;
      }).join('');

      const serversHTML = detail.servers.map(s => {
        const tagOpen = s.editUrl
          ? `<a class="dash-card-server text-reset text-decoration-none" href="${esc(s.editUrl)}" data-drill-server="${esc(s.ip)}">`
          : `<div class="dash-card-server" data-drill-server="${esc(s.ip)}">`;
        const tagClose = s.editUrl ? `</a>` : `</div>`;
        return `${tagOpen}
          <span class="dash-flow-port-dot dash-flow-dot-srv" aria-hidden="true"></span>
          <div class="dash-hub-server-head">
            <span class="dash-server-dot" aria-hidden="true"></span>
            ${metricIcon('server')}
            <span class="dash-flow-title text-truncate" title="${esc(s.name)}">${esc(s.name)}</span>
          </div>
          <div class="dash-flow-item-sub"><span class="dash-flow-host font-monospace text-truncate">${esc(s.ip)}</span></div>
          <div class="dash-server-stats">
            <span><span class="dash-server-stat-val">${s.accounts}</span>账户</span>
            <span><span class="dash-server-stat-val">${s.connections}</span>连接</span>
          </div>
        ${tagClose}`;
      }).join('');

      drillStage.innerHTML = `
        <div class="dash-drill-desktop">
          <div class="dash-tier">
            <div class="dash-tier-label">${metricIcon('world')}<span>账户国家（点击锁定筛选）</span></div>
            <div class="dash-tier-clients">${clientsHTML}</div>
          </div>
          <div class="dash-tier">
            <div class="dash-tier-label">${metricIcon('route')}<span>在线部署位</span></div>
            <div class="dash-tier-placements">${placementsHTML}</div>
          </div>
          <div class="dash-tier">
            <div class="dash-tier-label">${metricIcon('server')}<span>目标服务器</span></div>
            <div class="dash-tier-servers">${serversHTML}</div>
          </div>
        </div>
        <div class="dash-drill-mobile">
          <div class="dash-mdot-wrap">
            <svg class="dash-mdot-svg" role="img" aria-label="移动端节点拓扑图" data-mdot-svg></svg>
          </div>
          <div class="dash-mdot-detail" data-mdot-detail></div>
        </div>`;

      renderMobileDotGraph(detail);
      applyMobileSelect(detail);
      scheduleDrillDraw();
    }

    // 移动端一屏点状交互拓扑图（顶层来源国家圆点 → 中层部署位圆点 → 底层服务器圆点）
    function renderMobileDotGraph(detail) {
      const svg = drillStage.querySelector('[data-mdot-svg]');
      if (!svg) return;
      const W = 360;
      const plCount = detail.placements.length;
      const plRows = plCount > 9 ? 2 : 1;
      const ySrc = 44;
      const yPlStart = plRows === 2 ? 124 : 138;
      const plRowGap = 36;
      const ySrv = plRows === 2 ? 236 : 230;
      const H = ySrv + 44;
      svg.setAttribute('viewBox', `0 0 ${W} ${H}`);

      const spreadX = (count, pad) => {
        if (count <= 1) return [W / 2];
        const span = W - pad * 2;
        return Array.from({ length: count }, (_, i) => pad + (span * i) / (count - 1));
      };

      const srcPos = new Map();
      const srcXs = spreadX(detail.clientCountries.length, 46);
      detail.clientCountries.forEach((c, i) => srcPos.set(c.code, [srcXs[i], ySrc]));

      const plPos = new Map();
      if (plRows === 1) {
        const xs = spreadX(plCount, plCount <= 3 ? 64 : 26);
        detail.placements.forEach((p, i) => plPos.set(p.key, [xs[i], yPlStart]));
      } else {
        const topN = Math.ceil(plCount / 2);
        const botN = plCount - topN;
        const topXs = spreadX(topN, 24);
        const botXs = spreadX(botN, 34);
        detail.placements.forEach((p, i) => {
          if (i < topN) plPos.set(p.key, [topXs[i], yPlStart]);
          else plPos.set(p.key, [botXs[i - topN], yPlStart + plRowGap]);
        });
      }

      const srvPos = new Map();
      const srvXs = spreadX(detail.servers.length, detail.servers.length <= 2 ? 84 : 38);
      detail.servers.forEach((s, i) => srvPos.set(s.ip, [srvXs[i], ySrv]));

      const frag = document.createDocumentFragment();

      // 层级轻提示文字
      const addTierLbl = (text, y) => {
        const t = svgEl('text', { class: 'dash-mdot-tier-lbl', x: 10, y });
        t.textContent = text;
        frag.appendChild(t);
      };
      addTierLbl('来源', ySrc - 24);
      addTierLbl('部署位', yPlStart - 20);
      addTierLbl('服务器', ySrv - 22);

      // 连线层
      const edgesG = svgEl('g', {});
      let idx = 0;
      const addEdge = (kind, fromKey, toKey, p1, p2, weight) => {
        if (!p1 || !p2) return;
        const [x1, y1] = p1;
        const [x2, y2] = p2;
        const dy = Math.max(20, (y2 - y1) * 0.48);
        const d = `M ${x1.toFixed(1)} ${y1.toFixed(1)} C ${x1.toFixed(1)} ${(y1 + dy).toFixed(1)}, ${x2.toFixed(1)} ${(y2 - dy).toFixed(1)}, ${x2.toFixed(1)} ${y2.toFixed(1)}`;
        const w = Math.max(1, Math.min(4, Number(weight) || 1));
        const cls = kind === 'client'
          ? `dash-flow-edge dash-flow-edge-client dash-flow-w${w}`
          : `dash-flow-edge dash-flow-w${w}`;
        const g = svgEl('g', { class: cls, 'data-medge-kind': kind, 'data-medge-from': fromKey, 'data-medge-to': toKey });
        g.appendChild(svgEl('path', { class: 'dash-flow-track', d }));
        g.appendChild(svgEl('path', { class: 'dash-flow-stream', d }));
        if (!reducedMotion) {
          const dur = (2.6 - w * 0.25).toFixed(2) + 's';
          const begin = `-${((idx * 0.45) % 2.1).toFixed(2)}s`;
          const dot = svgEl('circle', { class: 'dash-flow-pulse', r: (1.3 + w * 0.14).toFixed(2) });
          dot.appendChild(svgEl('animateMotion', { dur, begin, repeatCount: 'indefinite', path: d }));
          g.appendChild(dot);
        }
        idx++;
        edgesG.appendChild(g);
      };
      for (const lk of detail.clientLinks) addEdge('client', lk.country, lk.placement, srcPos.get(lk.country), plPos.get(lk.placement), lk.weight);
      for (const lk of detail.serverLinks) addEdge('server', lk.placement, lk.serverIp, plPos.get(lk.placement), srvPos.get(lk.serverIp), lk.weight);
      frag.appendChild(edgesG);

      // 节点圆点层
      const nodesG = svgEl('g', {});
      const maxPlAcc = Math.max(1, ...detail.placements.map(p => p.accounts));

      // 1) 来源国家点
      for (const c of detail.clientCountries) {
        const [x, y] = srcPos.get(c.code);
        const g = svgEl('g', { class: 'dash-mdot-node', 'data-mdot-type': 'client', 'data-mdot-id': c.code, role: 'button', tabindex: '0' });
        g.appendChild(svgEl('circle', { class: 'dash-mdot-hit', cx: x.toFixed(1), cy: y.toFixed(1), r: 18 }));
        g.appendChild(svgEl('circle', { class: 'dash-mdot-ring', cx: x.toFixed(1), cy: y.toFixed(1), r: 9.5 }));
        g.appendChild(svgEl('circle', { class: 'dash-mdot-c-src', cx: x.toFixed(1), cy: y.toFixed(1), r: 4.8 }));
        const lbl = svgEl('text', { class: 'dash-mdot-lbl', x: x.toFixed(1), y: (y - 12).toFixed(1), 'text-anchor': 'middle' });
        lbl.textContent = `${c.name} ${c.accounts}`;
        g.appendChild(lbl);
        nodesG.appendChild(g);
      }

      // 2) 部署位圆点（按账号数微调大小；点击查看详情）
      for (const p of detail.placements) {
        const [x, y] = plPos.get(p.key);
        const r = 4.2 + (p.accounts / maxPlAcc) * 3.2;
        const g = svgEl('g', { class: 'dash-mdot-node', 'data-mdot-type': 'placement', 'data-mdot-id': p.key, role: 'button', tabindex: '0' });
        g.appendChild(svgEl('circle', { class: 'dash-mdot-hit', cx: x.toFixed(1), cy: y.toFixed(1), r: 16 }));
        g.appendChild(svgEl('circle', { class: 'dash-mdot-ring', cx: x.toFixed(1), cy: y.toFixed(1), r: (r + 4.2).toFixed(1) }));
        g.appendChild(svgEl('circle', { class: 'dash-mdot-c-pl', cx: x.toFixed(1), cy: y.toFixed(1), r: r.toFixed(1) }));
        if (plCount <= 5) {
          const lbl = svgEl('text', { class: 'dash-mdot-lbl-sub', x: x.toFixed(1), y: (y - r - 5).toFixed(1), 'text-anchor': 'middle' });
          lbl.textContent = truncText(p.subName, 9);
          g.appendChild(lbl);
        }
        nodesG.appendChild(g);
      }

      // 3) 目标服务器圆点
      for (const s of detail.servers) {
        const [x, y] = srvPos.get(s.ip);
        const g = svgEl('g', { class: 'dash-mdot-node dash-mdot-node-srv', 'data-mdot-type': 'server', 'data-mdot-id': s.ip, role: 'button', tabindex: '0' });
        g.appendChild(svgEl('circle', { class: 'dash-mdot-hit', cx: x.toFixed(1), cy: y.toFixed(1), r: 18 }));
        g.appendChild(svgEl('circle', { class: 'dash-mdot-ring', cx: x.toFixed(1), cy: y.toFixed(1), r: 10.5 }));
        g.appendChild(svgEl('circle', { class: 'dash-mdot-c-srv', cx: x.toFixed(1), cy: y.toFixed(1), r: 5.5 }));
        const shortName = truncText(s.name, 7);
        const lbl = svgEl('text', { class: 'dash-mdot-lbl', x: x.toFixed(1), y: (y + 18).toFixed(1), 'text-anchor': 'middle' });
        lbl.textContent = shortName;
        const sub = svgEl('text', { class: 'dash-mdot-lbl-sub', x: x.toFixed(1), y: (y + 29).toFixed(1), 'text-anchor': 'middle' });
        sub.textContent = `${s.accounts} 账户`;
        g.appendChild(lbl);
        g.appendChild(sub);
        nodesG.appendChild(g);
      }

      frag.appendChild(nodesG);
      svg.replaceChildren(frag);
    }

    function applyMobileSelect(detail) {
      const svg = drillStage.querySelector('[data-mdot-svg]');
      const panel = drillStage.querySelector('[data-mdot-detail]');
      if (!svg || !panel) return;

      const hasFocus = mobileSelect !== null;
      svg.classList.toggle('has-focus', hasFocus);

      const activeClients = new Set();
      const activePlacements = new Set();
      const activeServers = new Set();

      if (hasFocus) {
        if (mobileSelect.type === 'client') {
          activeClients.add(mobileSelect.id);
          for (const lk of detail.clientLinks) if (lk.country === mobileSelect.id) activePlacements.add(lk.placement);
          for (const lk of detail.serverLinks) if (activePlacements.has(lk.placement)) activeServers.add(lk.serverIp);
        } else if (mobileSelect.type === 'placement') {
          activePlacements.add(mobileSelect.id);
          for (const lk of detail.clientLinks) if (lk.placement === mobileSelect.id) activeClients.add(lk.country);
          for (const lk of detail.serverLinks) if (lk.placement === mobileSelect.id) activeServers.add(lk.serverIp);
        } else if (mobileSelect.type === 'server') {
          activeServers.add(mobileSelect.id);
          for (const lk of detail.serverLinks) if (lk.serverIp === mobileSelect.id) activePlacements.add(lk.placement);
          for (const lk of detail.clientLinks) if (activePlacements.has(lk.placement)) activeClients.add(lk.country);
        }
      }

      svg.querySelectorAll('[data-mdot-type]').forEach(el => {
        const tp = el.dataset.mdotType;
        const id = el.dataset.mdotId;
        const hit = (tp === 'client' && activeClients.has(id)) ||
          (tp === 'placement' && activePlacements.has(id)) ||
          (tp === 'server' && activeServers.has(id));
        el.classList.toggle('is-active', hit);
      });
      svg.querySelectorAll('[data-medge-kind]').forEach(g => {
        let hit = false;
        if (hasFocus) {
          hit = g.dataset.medgeKind === 'client'
            ? (activeClients.has(g.dataset.medgeFrom) && activePlacements.has(g.dataset.medgeTo))
            : (activePlacements.has(g.dataset.medgeFrom) && activeServers.has(g.dataset.medgeTo));
        }
        g.classList.toggle('is-active', hit);
      });

      if (!hasFocus) {
        panel.innerHTML = `<div class="dash-mdot-hint">点按上方任意圆点查看详情与链路</div>`;
        return;
      }

      const clientName = new Map(detail.clientCountries.map(c => [c.code, c.name]));
      const serverByIP = new Map(detail.servers.map(s => [s.ip, s]));

      if (mobileSelect.type === 'placement') {
        const p = detail.placements.find(x => x.key === mobileSelect.id);
        if (!p) return;
        const srv = serverByIP.get(p.serverIp);
        const host = p.domain || 'IP 直连';
        const port = p.port ? `:${p.port}` : '';
        const conns = p.connKnown ? `${p.connections} 连接` : '连接数未知';
        const srcs = (p.clientCountries || []).map(c => clientName.get(c) || c).join('、');
        panel.innerHTML = `
          <div class="dash-mdot-detail-head">
            <span class="dash-mdot-detail-kind">部署位</span>
            <span class="dash-flow-title text-truncate">${esc(p.subName)}</span>
            <a class="dash-mdot-detail-link" href="${esc(p.editUrl)}">编辑</a>
          </div>
          <div class="dash-mdot-detail-sub">
            <span class="font-monospace text-truncate">${esc(host + port)}</span>
            <span>${p.accounts} 账户 · ${conns}</span>
          </div>
          <div class="dash-mdot-detail-sub">
            ${srcs ? `<span>来源：${esc(srcs)}</span>` : ''}
            ${srv ? `<span>服务器：${esc(srv.name)}（${esc(srv.ip)}）</span>` : ''}
          </div>`;
      } else if (mobileSelect.type === 'server') {
        const s = detail.servers.find(x => x.ip === mobileSelect.id);
        if (!s) return;
        panel.innerHTML = `
          <div class="dash-mdot-detail-head">
            <span class="dash-mdot-detail-kind">服务器</span>
            <span class="dash-flow-title text-truncate">${esc(s.name)}</span>
            ${s.editUrl ? `<a class="dash-mdot-detail-link" href="${esc(s.editUrl)}">编辑</a>` : ''}
          </div>
          <div class="dash-mdot-detail-sub">
            <span class="font-monospace">${esc(s.ip)}</span>
            <span>${s.accounts} 账户 · ${s.connections} 连接 · ${activePlacements.size} 个在线部署位</span>
          </div>`;
      } else if (mobileSelect.type === 'client') {
        const c = detail.clientCountries.find(x => x.code === mobileSelect.id);
        if (!c) return;
        panel.innerHTML = `
          <div class="dash-mdot-detail-head">
            <span class="dash-mdot-detail-kind">账户国家</span>
            <span class="dash-flow-title text-truncate">${esc(c.name)}</span>
          </div>
          <div class="dash-mdot-detail-sub">
            <span>${c.accounts} 账户 · ${c.connections} 连接 · 连入 ${activePlacements.size} 个部署位 / ${activeServers.size} 台服务器</span>
          </div>`;
      }
    }

    function closeCountry() {
      currentCountry = null;
      drillHover = null;
      drillLockClient = null;
      mobileSelect = null;
      drillView.hidden = true;
      mapView.hidden = false;
      drillSvg.replaceChildren();
    }

    function applyDrillFocus() {
      if (!currentCountry) return;
      const detail = topo.details?.[currentCountry];
      if (!detail) return;
      const activeFocus = drillHover || (drillLockClient ? { type: 'client', id: drillLockClient } : null);
      const hasFocus = activeFocus !== null;
      drillCanvas.classList.toggle('has-focus', hasFocus);

      const activeClients = new Set();
      const activePlacements = new Set();
      const activeServers = new Set();

      if (hasFocus) {
        if (activeFocus.type === 'client') {
          activeClients.add(activeFocus.id);
          for (const lk of detail.clientLinks) {
            if (lk.country === activeFocus.id) activePlacements.add(lk.placement);
          }
          for (const lk of detail.serverLinks) {
            if (activePlacements.has(lk.placement)) activeServers.add(lk.serverIp);
          }
        } else if (activeFocus.type === 'placement') {
          activePlacements.add(activeFocus.id);
          for (const lk of detail.clientLinks) {
            if (lk.placement === activeFocus.id) activeClients.add(lk.country);
          }
          for (const lk of detail.serverLinks) {
            if (lk.placement === activeFocus.id) activeServers.add(lk.serverIp);
          }
        } else if (activeFocus.type === 'server') {
          activeServers.add(activeFocus.id);
          for (const lk of detail.serverLinks) {
            if (lk.serverIp === activeFocus.id) activePlacements.add(lk.placement);
          }
          for (const lk of detail.clientLinks) {
            if (activePlacements.has(lk.placement)) activeClients.add(lk.country);
          }
        }
      }

      drillStage.querySelectorAll('[data-drill-client]').forEach(el => {
        el.classList.toggle('is-active', activeClients.has(el.dataset.drillClient));
        el.classList.toggle('is-locked', drillLockClient === el.dataset.drillClient);
      });
      drillStage.querySelectorAll('[data-drill-placement]').forEach(el => el.classList.toggle('is-active', activePlacements.has(el.dataset.drillPlacement)));
      drillStage.querySelectorAll('[data-drill-server]').forEach(el => el.classList.toggle('is-active', activeServers.has(el.dataset.drillServer)));
      drillSvg.querySelectorAll('[data-edge-kind]').forEach(g => {
        let hit = false;
        if (hasFocus) {
          if (g.dataset.edgeKind === 'client') {
            hit = activeClients.has(g.dataset.edgeFrom) && activePlacements.has(g.dataset.edgeTo);
          } else {
            hit = activePlacements.has(g.dataset.edgeFrom) && activeServers.has(g.dataset.edgeTo);
          }
        }
        g.classList.toggle('is-active', hit);
      });
    }

    function drawDrillEdges() {
      rafId = 0;
      if (!currentCountry || drillView.hidden) return;
      const detail = topo.details?.[currentCountry];
      if (!detail) return;
      const desktopEl = drillStage.querySelector('.dash-drill-desktop');
      if (!desktopEl || desktopEl.offsetWidth === 0) {
        drillSvg.replaceChildren();
        return;
      }
      const box = drillCanvas.getBoundingClientRect();
      if (box.width <= 0 || box.height <= 0) return;

      drillSvg.setAttribute('viewBox', `0 0 ${box.width} ${box.height}`);
      const clientEls = new Map();
      drillStage.querySelectorAll('[data-drill-client]').forEach(el => clientEls.set(el.dataset.drillClient, el));
      const placementEls = new Map();
      drillStage.querySelectorAll('[data-drill-placement]').forEach(el => placementEls.set(el.dataset.drillPlacement, el));
      const serverEls = new Map();
      drillStage.querySelectorAll('[data-drill-server]').forEach(el => serverEls.set(el.dataset.drillServer, el));

      const frag = document.createDocumentFragment();
      let idx = 0;
      const addCurve = (kind, fromKey, toKey, fromDot, toDot, weight) => {
        if (!fromDot || !toDot) return;
        const r1 = fromDot.getBoundingClientRect();
        const r2 = toDot.getBoundingClientRect();
        const x1 = r1.left + r1.width / 2 - box.left;
        const y1 = r1.top + r1.height / 2 - box.top;
        const x2 = r2.left + r2.width / 2 - box.left;
        const y2 = r2.top + r2.height / 2 - box.top;
        if (y2 <= y1 + 8) return;
        const dy = Math.max(28, (y2 - y1) * 0.48);
        const d = `M ${x1.toFixed(1)} ${y1.toFixed(1)} C ${x1.toFixed(1)} ${(y1 + dy).toFixed(1)}, ${x2.toFixed(1)} ${(y2 - dy).toFixed(1)}, ${x2.toFixed(1)} ${y2.toFixed(1)}`;
        const w = Math.max(1, Math.min(4, Number(weight) || 1));
        const cls = kind === 'client'
          ? `dash-flow-edge dash-flow-edge-client dash-flow-w${w}`
          : `dash-flow-edge dash-flow-w${w}`;
        const g = svgEl('g', {
          class: cls,
          'data-edge-kind': kind,
          'data-edge-from': fromKey,
          'data-edge-to': toKey
        });
        g.appendChild(svgEl('path', { class: 'dash-flow-track', d }));
        g.appendChild(svgEl('path', { class: 'dash-flow-stream', d }));
        if (!reducedMotion) {
          const dur = (2.6 - w * 0.28).toFixed(2) + 's';
          const begin = `-${((idx * 0.45) % 2.1).toFixed(2)}s`;
          const dot = svgEl('circle', { class: 'dash-flow-pulse', r: (1.45 + w * 0.18).toFixed(2) });
          dot.appendChild(svgEl('animateMotion', { dur, begin, repeatCount: 'indefinite', path: d }));
          g.appendChild(dot);
        }
        idx++;
        frag.appendChild(g);
      };

      for (const lk of detail.clientLinks) {
        const cEl = clientEls.get(lk.country);
        const pEl = placementEls.get(lk.placement);
        if (cEl && pEl) {
          addCurve('client', lk.country, lk.placement, cEl.querySelector('.dash-flow-dot-src') || cEl, pEl.querySelector('.dash-flow-dot-in') || pEl, lk.weight);
        }
      }
      for (const lk of detail.serverLinks) {
        const pEl = placementEls.get(lk.placement);
        const sEl = serverEls.get(lk.serverIp);
        if (pEl && sEl) {
          addCurve('server', lk.placement, lk.serverIp, pEl.querySelector('.dash-flow-dot-out') || pEl, sEl.querySelector('.dash-flow-dot-srv') || sEl, lk.weight);
        }
      }

      drillSvg.replaceChildren(frag);
      applyDrillFocus();
    }

    function scheduleDrillDraw() {
      if (rafId) cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(drawDrillEdges);
    }

    // 事件绑定
    const onRootClick = event => {
      const dstNode = event.target.closest('[data-map-dst]');
      if (dstNode && mapSvg.contains(dstNode)) {
        openCountry(dstNode.dataset.mapDst);
        return;
      }
      const selectBtn = event.target.closest('[data-select-country]');
      if (selectBtn && root.contains(selectBtn)) {
        openCountry(selectBtn.dataset.selectCountry);
        return;
      }
      const switchBtn = event.target.closest('[data-switch-country]');
      if (switchBtn && root.contains(switchBtn)) {
        openCountry(switchBtn.dataset.switchCountry);
        return;
      }
      const drillClientBtn = event.target.closest('[data-drill-client]');
      if (drillClientBtn && drillStage.contains(drillClientBtn)) {
        const c = drillClientBtn.dataset.drillClient;
        drillLockClient = drillLockClient === c ? null : c;
        applyDrillFocus();
        return;
      }
      const mdot = event.target.closest('[data-mdot-type]');
      if (mdot && drillStage.contains(mdot) && currentCountry) {
        const detail = topo.details?.[currentCountry];
        if (!detail) return;
        const next = { type: mdot.dataset.mdotType, id: mdot.dataset.mdotId };
        mobileSelect = (mobileSelect && mobileSelect.type === next.type && mobileSelect.id === next.id) ? null : next;
        applyMobileSelect(detail);
        return;
      }
      if (event.target.closest('[data-dash-back]')) {
        closeCountry();
      }
    };

    const onKeyDown = event => {
      if (event.key === 'Escape' && currentCountry) {
        closeCountry();
        return;
      }
      if (event.key === 'Enter' || event.key === ' ') {
        const dstNode = event.target.closest('[data-map-dst]');
        if (dstNode && mapSvg.contains(dstNode)) {
          event.preventDefault();
          openCountry(dstNode.dataset.mapDst);
        }
      }
    };

    const onMapOver = event => {
      const dst = event.target.closest('[data-map-dst]');
      if (dst && mapSvg.contains(dst)) {
        mapFocus = { type: 'dst', code: dst.dataset.mapDst };
        applyMapFocus();
        return;
      }
      const src = event.target.closest('[data-map-src]');
      if (src && mapSvg.contains(src)) {
        mapFocus = { type: 'src', code: src.dataset.mapSrc };
        applyMapFocus();
      }
    };
    const onMapOut = event => {
      if (!event.relatedTarget || !mapSvg.contains(event.relatedTarget) ||
          !event.relatedTarget.closest('[data-map-dst], [data-map-src]')) {
        mapFocus = null;
        applyMapFocus();
      }
    };

    const onDrillOver = event => {
      const cl = event.target.closest('[data-drill-client]');
      if (cl && drillStage.contains(cl)) {
        drillHover = { type: 'client', id: cl.dataset.drillClient };
        applyDrillFocus();
        return;
      }
      const pl = event.target.closest('[data-drill-placement]');
      if (pl && drillStage.contains(pl)) {
        drillHover = { type: 'placement', id: pl.dataset.drillPlacement };
        applyDrillFocus();
        return;
      }
      const sv = event.target.closest('[data-drill-server]');
      if (sv && drillStage.contains(sv)) {
        drillHover = { type: 'server', id: sv.dataset.drillServer };
        applyDrillFocus();
      }
    };
    const onDrillOut = event => {
      if (!event.relatedTarget || !drillStage.contains(event.relatedTarget) ||
          !event.relatedTarget.closest('[data-drill-client], [data-drill-placement], [data-drill-server]')) {
        drillHover = null;
        applyDrillFocus();
      }
    };

    root.addEventListener('click', onRootClick);
    root.addEventListener('keydown', onKeyDown);
    mapSvg.addEventListener('mouseover', onMapOver);
    mapSvg.addEventListener('focusin', onMapOver);
    mapSvg.addEventListener('mouseout', onMapOut);
    mapSvg.addEventListener('focusout', onMapOut);
    drillStage.addEventListener('mouseover', onDrillOver);
    drillStage.addEventListener('focusin', onDrillOver);
    drillStage.addEventListener('mouseout', onDrillOut);
    drillStage.addEventListener('focusout', onDrillOut);

    let ro = null;
    if (typeof ResizeObserver === 'function') {
      ro = new ResizeObserver(scheduleDrillDraw);
      ro.observe(root);
    }
    window.addEventListener('resize', scheduleDrillDraw);

    renderWorldMap();

    return () => {
      if (rafId) cancelAnimationFrame(rafId);
      if (ro) ro.disconnect();
      window.removeEventListener('resize', scheduleDrillDraw);
      root.removeEventListener('click', onRootClick);
      root.removeEventListener('keydown', onKeyDown);
      mapSvg.removeEventListener('mouseover', onMapOver);
      mapSvg.removeEventListener('focusin', onMapOver);
      mapSvg.removeEventListener('mouseout', onMapOut);
      mapSvg.removeEventListener('focusout', onMapOut);
      drillStage.removeEventListener('mouseover', onDrillOver);
      drillStage.removeEventListener('focusin', onDrillOver);
      drillStage.removeEventListener('mouseout', onDrillOut);
      drillStage.removeEventListener('focusout', onDrillOut);
    };
  }

  window.AirOpsPage.register(init);
})();
