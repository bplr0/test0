(function() {
  // Install once per page
  if (window.__rectSelectInstalled) return;
  window.__rectSelectInstalled = true;

  const LONG_PRESS_MS = 220;
  const MOVE_CANCEL_PX = 14;

  let timer = null;
  let pressing = false;
  let active = false;
  let sx = 0, sy = 0, cx = 0, cy = 0;

  function clearTimer(){ if (timer) { clearTimeout(timer); timer = null; } }

  const layer = document.createElement('div');
  layer.style.position = 'fixed';
  layer.style.inset = '0';
  layer.style.zIndex = '2147483646';
  layer.style.pointerEvents = 'none';
  layer.style.touchAction = 'none';
  layer.style.overflow = 'hidden';
  layer.style.display = 'none';

  const DOT_SIZE = 3;
  const DOT_COLOR = 'rgba(120,120,120,.75)';
  function makeDot(){
    const d = document.createElement('div');
    d.style.position = 'absolute';
    d.style.width = DOT_SIZE + 'px';
    d.style.height = DOT_SIZE + 'px';
    d.style.borderRadius = '999px';
    d.style.background = DOT_COLOR;
    d.style.left = '0px';
    d.style.top = '0px';
    return d;
  }
  const dotTL = makeDot();
  const dotTR = makeDot();
  const dotBL = makeDot();
  const dotBR = makeDot();
  layer.appendChild(dotTL);
  layer.appendChild(dotTR);
  layer.appendChild(dotBL);
  layer.appendChild(dotBR);
  (document.body || document.documentElement).appendChild(layer);

  function show(){ layer.style.display = 'block'; }
  function hide(){ layer.style.display = 'none'; }
  function setRect(x1,y1,x2,y2){
    const left = Math.min(x1,x2);
    const top  = Math.min(y1,y2);
    const right = Math.max(x1,x2);
    const bottom = Math.max(y1,y2);
    const r = DOT_SIZE / 2;
    dotTL.style.left = (left - r) + 'px';
    dotTL.style.top  = (top - r) + 'px';
    dotTR.style.left = (right - r) + 'px';
    dotTR.style.top  = (top - r) + 'px';
    dotBL.style.left = (left - r) + 'px';
    dotBL.style.top  = (bottom - r) + 'px';
    dotBR.style.left = (right - r) + 'px';
    dotBR.style.top  = (bottom - r) + 'px';
  }

  function intersects(r, L, T, R, B){
    return (r.right > L && r.left < R && r.bottom > T && r.top < B);
  }

  function extractTextInRect(x0,y0,x1,y1){
    const L = Math.min(x0,x1), T = Math.min(y0,y1);
    const R = Math.max(x0,x1), B = Math.max(y0,y1);
    if ((R-L) < 8 || (B-T) < 8) return '';

    const out = [];
    const seen = new Set();
    const root = document.body || document.documentElement;

    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        if (!node || !node.nodeValue) return NodeFilter.FILTER_REJECT;
        const txt = node.nodeValue.trim();
        if (!txt) return NodeFilter.FILTER_REJECT;
        const p = node.parentElement;
        if (!p) return NodeFilter.FILTER_REJECT;
        const tag = (p.tagName || '').toLowerCase();
        if (tag === 'script' || tag === 'style' || tag === 'noscript') return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });

    const range = document.createRange();
    while (walker.nextNode()) {
      const n = walker.currentNode;
      try {
        range.selectNodeContents(n);
        const rects = range.getClientRects();
        for (const rr of rects) {
          if (intersects(rr, L, T, R, B)) {
            const norm = n.nodeValue.replace(/\s+/g,' ').trim();
            if (norm && !seen.has(norm)) { seen.add(norm); out.push(norm); }
            break;
          }
        }
      } catch (_) {}
    }
    return out.join('\n').trim();
  }

  function activate(){
    active = true;
    show();
    setRect(sx,sy,cx,cy);
    document.documentElement.style.userSelect = 'none';
    document.documentElement.style.webkitUserSelect = 'none';
    document.documentElement.style.touchAction = 'none';
  }

  function deactivate(){
    active = false;
    hide();
    document.documentElement.style.userSelect = '';
    document.documentElement.style.webkitUserSelect = '';
    document.documentElement.style.touchAction = '';
  }

  function startPress(x,y, target){
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return;
    pressing = true;
    active = false;
    sx = cx = x; sy = cy = y;

    clearTimer();
    timer = setTimeout(() => {
      timer = null;
      if (!pressing) return;
      activate();
    }, LONG_PRESS_MS);
  }

  function movePress(x,y, ev){
    if (!pressing) return;
    cx = x; cy = y;

    if (!active && timer) {
      const dx = cx - sx, dy = cy - sy;
      if (Math.hypot(dx,dy) > MOVE_CANCEL_PX) { pressing = false; clearTimer(); return; }
    }

    if (active) {
      setRect(sx,sy,cx,cy);
      if (ev && ev.cancelable) ev.preventDefault();
      if (ev) ev.stopPropagation();
    }
  }

  function endPress(ev){
    clearTimer();
    if (!pressing) return;
    pressing = false;

    if (active) {
      const text = extractTextInRect(sx,sy,cx,cy);
      try {
        if (window.AndroidBridge && typeof window.AndroidBridge.onSel === 'function') {
          window.AndroidBridge.onSel(JSON.stringify({
            type: 'sel_end',
            url: location.href,
            text: text
          }));
        }
      } catch (_) {}
      deactivate();
      if (ev && ev.cancelable) ev.preventDefault();
      if (ev) ev.stopPropagation();
    }
  }

  function cancelPress(){
    clearTimer();
    pressing = false;
    if (active) deactivate();
  }

  // Capture pointer events: works on Chrome/Android WebView
  if ('PointerEvent' in window) {
    window.addEventListener('pointerdown', (e) => startPress(e.clientX, e.clientY, e.target), { capture: true, passive: false });
    window.addEventListener('pointermove', (e) => movePress(e.clientX, e.clientY, e), { capture: true, passive: false });
    window.addEventListener('pointerup',   (e) => endPress(e), { capture: true, passive: false });
    window.addEventListener('pointercancel', cancelPress, { capture: true, passive: true });
  } else {
    window.addEventListener('touchstart', (ev) => {
      const t = ev.touches && ev.touches[0]; if (!t) return;
      startPress(t.clientX, t.clientY, ev.target);
    }, { capture: true, passive: false });
    window.addEventListener('touchmove', (ev) => {
      const t = ev.touches && ev.touches[0]; if (!t) return;
      movePress(t.clientX, t.clientY, ev);
    }, { capture: true, passive: false });
    window.addEventListener('touchend', (ev) => endPress(ev), { capture: true, passive: false });
    window.addEventListener('touchcancel', cancelPress, { capture: true, passive: true });
  }
})();
