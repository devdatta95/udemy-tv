/*
 * Udemy TV - page helper injected into every page by the app.
 * The app handles all remote keys natively and calls into window.__tv.
 *
 *   state()                 page info for the app: { v: video on page, fs: fullscreen, path }
 *   hit(fx, fy)             what OK under the pointer should do: text box / fullscreen / handled / null (= real tap)
 *   setText(id, value)      fill a text field from the app's native input box (React-safe)
 *   scrollPoint(fx,fy,v,d)  where to scroll so side panels (lecture list) scroll, not the header
 *   media(action)           video actions, returns a message for the on-screen display
 *   nav*(…)                 Navigation mode (YouTube-style focus, no pointer)
 * Fullscreen: the app sends a real F12 key; the handler below makes the <video> fullscreen
 * (a real key press counts as a user action, which browsers require for fullscreen).
 */
(function () {
  if (window.__tv) return;

  var RATES = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
  var TEXT_TYPES = /^(text|search|email|password|number|url|tel|)$/;
  var CLICKABLE = 'a[href],button,[role=button],[role=link],[role=tab],[role=menuitem],[role=menuitemradio],' +
    '[role=menuitemcheckbox],[role=option],[role=checkbox],[role=radio],[role=switch],[role=slider],input,select,' +
    'label,summary,[tabindex]:not([tabindex="-1"])';
  var cssFs = false;
  var editSeq = 0;
  var savedTracks = null;

  /* ============================================================ fit to TV */
  function applyViewport() {
    var w = window.__tvWidth;
    if (!w || window.top !== window || !document.head) return;
    var m = document.head.querySelector('meta[name=viewport]');
    var content = 'width=' + w;
    if (!m) {
      m = document.createElement('meta');
      m.name = 'viewport';
      document.head.appendChild(m);
    }
    if (m.getAttribute('content') !== content) m.setAttribute('content', content);
  }

  function onReady() {
    css();
    applyViewport();
    hideSkipLinks();
    if (document.head) {
      new MutationObserver(applyViewport).observe(document.head, {
        childList: true, subtree: true, attributes: true, attributeFilter: ['content']
      });
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', onReady);
  else onReady();

  /* ============================================================ styles */
  function css() {
    if (document.getElementById('__tvcss') || !document.documentElement) return;
    var s = document.createElement('style');
    s.id = '__tvcss';
    s.textContent =
      'a[href="#main-content-anchor"],[data-purpose="skip-to-content"],[class*="skip-to-main"],[class*="skip-link"]' +
      '{display:none!important}' +
      // Navigation mode focus ring
      '.__tvf{outline:4px solid #a435f0!important;outline-offset:2px!important;' +
      'box-shadow:0 0 0 9px rgba(164,53,240,.28)!important;border-radius:6px}' +
      // Fallback fullscreen (only if real fullscreen is refused): only the video is shown
      'html.__tvfs,html.__tvfs body{background:#000!important;overflow:hidden!important}' +
      'html.__tvfs body *{visibility:hidden!important}' +
      'html.__tvfs .__tvanc{transform:none!important;filter:none!important;perspective:none!important;' +
      'contain:none!important;will-change:auto!important;backdrop-filter:none!important}' +
      'html.__tvfs video.__tvv{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;' +
      'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;' +
      'z-index:2147483647!important;object-fit:contain!important;background:#000!important;transform:none!important}';
    (document.head || document.documentElement).appendChild(s);
  }

  function hideSkipLinks() {
    var els = document.querySelectorAll('a[href^="#"],[class*="skip"]');
    for (var i = 0; i < els.length; i++) {
      var t = (els[i].textContent || '').trim();
      if (t.length < 40 && /^skip to/i.test(t)) {
        els[i].style.setProperty('display', 'none', 'important');
        if (document.activeElement === els[i]) els[i].blur();
      }
    }
  }

  /* ============================================================ video */
  function video() {
    var vs = document.querySelectorAll('video'), best = null, area = 0;
    for (var i = 0; i < vs.length; i++) {
      var r = vs[i].getBoundingClientRect();
      if (r.width * r.height > area) { best = vs[i]; area = r.width * r.height; }
    }
    if (!best && document.fullscreenElement && document.fullscreenElement.tagName === 'VIDEO') best = document.fullscreenElement;
    return area > 0 || best ? best : null;
  }

  function fmt(s) {
    if (!isFinite(s)) return '--:--';
    s = Math.max(0, Math.floor(s));
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
    return (h ? h + ':' + (m < 10 ? '0' : '') : '') + m + ':' + (x < 10 ? '0' : '') + x;
  }

  function pos(v) { return fmt(v.currentTime) + ' / ' + fmt(v.duration); }

  // Udemy's player hides its controls until the mouse moves.
  function wake() {
    var v = video();
    if (!v) return false;
    var r = v.getBoundingClientRect();
    var ev = { bubbles: true, clientX: r.left + r.width / 2, clientY: r.top + r.height * 0.6 };
    v.dispatchEvent(new MouseEvent('mousemove', ev));
    for (var p = v.parentElement, i = 0; p && i < 3; p = p.parentElement, i++) p.dispatchEvent(new MouseEvent('mousemove', ev));
    return true;
  }

  function seek(sec) {
    var v = video();
    var d = isFinite(v.duration) ? v.duration : Infinity;
    v.currentTime = Math.min(Math.max(0, v.currentTime + sec), d - 0.5);
    return (sec > 0 ? '⏩  +' : '⏪  −') + Math.abs(sec) + ' s      ' + pos(v);
  }

  function playPause(force) {
    var v = video();
    var play = force === 'play' ? true : force === 'pause' ? false : v.paused;
    if (play) {
      var p = v.play();
      if (p && p.catch) p.catch(function () {});
      return '▶  Playing      ' + pos(v);
    }
    v.pause();
    return '⏸  Paused      ' + pos(v);
  }

  function rateMsg(r) { return 'Speed  ' + r + '×'; }

  function setSpeed(r) {
    var v = video();
    if (!v) return 'No video on this page';
    v.playbackRate = r;
    return rateMsg(r);
  }

  function speed(step) {
    var v = video(), cur = v.playbackRate, idx = 0, diff = Infinity;
    for (var i = 0; i < RATES.length; i++) {
      var d = Math.abs(RATES[i] - cur);
      if (d < diff) { diff = d; idx = i; }
    }
    var next = RATES[Math.min(RATES.length - 1, Math.max(0, idx + step))];
    v.playbackRate = next;
    return rateMsg(next) + (next === RATES[0] || next === RATES[RATES.length - 1] ? '  (limit)' : '');
  }

  function shown(el) {
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  function first(sels, needVisible) {
    for (var i = 0; i < sels.length; i++) {
      var els = document.querySelectorAll(sels[i]);
      for (var j = 0; j < els.length; j++) if (!needVisible || shown(els[j])) return els[j];
    }
    return null;
  }

  // Next / previous lecture: Udemy's player buttons, else the course-content list.
  function lecture(dir) {
    var btn = dir > 0
      ? first(['[data-purpose="go-to-next"]', '#go-to-next-item', '[aria-label*="next lecture" i]', '[aria-label*="go to next" i]'])
      : first(['[data-purpose="go-to-previous"]', '#go-to-previous-item', '[aria-label*="previous lecture" i]', '[aria-label*="go to previous" i]']);
    if (!btn) {
      var items = Array.prototype.slice.call(document.querySelectorAll('[data-purpose^="curriculum-item-"]'));
      var cur = -1;
      for (var i = 0; i < items.length; i++) {
        var it = items[i];
        if (/current/i.test(it.className) || it.getAttribute('aria-current') === 'true' || it.querySelector('[aria-current="true"]')) { cur = i; break; }
      }
      if (cur >= 0 && items[cur + dir]) btn = items[cur + dir];
    }
    if (!btn) return dir > 0 ? 'No next lecture found' : 'No previous lecture found';
    mouseClick(btn);
    return dir > 0 ? 'Next lecture  ⏭' : '⏮  Previous lecture';
  }

  // Captions: the video's text tracks; outside fullscreen fall back to Udemy's CC menu.
  function captions() {
    var v = video(), tt = v.textTracks, i;
    if (tt && tt.length) {
      var on = false;
      for (i = 0; i < tt.length; i++) if (tt[i].mode !== 'disabled') on = true;
      if (on) {
        savedTracks = [];
        for (i = 0; i < tt.length; i++) { savedTracks.push(tt[i].mode); tt[i].mode = 'disabled'; }
        return 'Captions off';
      }
      if (savedTracks && savedTracks.length === tt.length && savedTracks.some(function (m) { return m !== 'disabled'; })) {
        for (i = 0; i < tt.length; i++) tt[i].mode = isFullscreen() && savedTracks[i] === 'hidden' ? 'showing' : savedTracks[i];
      } else {
        var pick = 0;
        for (i = 0; i < tt.length; i++) if (/^en/i.test(tt[i].language || '') || /english/i.test(tt[i].label || '')) { pick = i; break; }
        tt[pick].mode = 'showing';
      }
      return 'Captions on';
    }
    if (!isFullscreen()) {
      var btn = first(['[data-purpose*="captions" i]', '[aria-label*="caption" i]', '[aria-label*="subtitle" i]']);
      if (btn) { wake(); mouseClick(btn); return 'Captions menu opened'; }
    }
    return 'Captions not available here (try Udemy’s CC button outside fullscreen)';
  }

  /* ============================================================ fullscreen */
  // A real F12 key from the app = user action, so the browser allows fullscreen.
  window.addEventListener('keydown', function (e) {
    if (e.key !== 'F12' || !e.isTrusted) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    var v = video();
    if (!v) return;
    try {
      var p = v.requestFullscreen ? v.requestFullscreen() : v.webkitEnterFullscreen && v.webkitEnterFullscreen();
      if (p && p.catch) p.catch(function () { cssFullscreen(true); });
    } catch (err) { cssFullscreen(true); }
  }, true);

  function cssFullscreen(on) {
    css();
    var marked = document.querySelectorAll('.__tvv,.__tvanc');
    for (var i = 0; i < marked.length; i++) marked[i].classList.remove('__tvv', '__tvanc');
    var v = video();
    if (on && v) {
      v.classList.add('__tvv');
      for (var p = v.parentElement; p && p !== document.documentElement; p = p.parentElement) p.classList.add('__tvanc');
      document.documentElement.classList.add('__tvfs');
      cssFs = true;
    } else {
      document.documentElement.classList.remove('__tvfs');
      cssFs = false;
    }
    return cssFs;
  }

  function isFullscreen() { return !!(document.fullscreenElement || document.webkitFullscreenElement) || cssFs; }

  function exitFullscreen() {
    if (cssFs) { cssFullscreen(false); return true; }
    if (document.fullscreenElement) { document.exitFullscreen(); return true; }
    return false;
  }

  /* ============================================================ text fields */
  function isText(el) {
    if (!el || el.disabled || el.readOnly) return false;
    if (el.isContentEditable) return true;
    if (el.tagName === 'TEXTAREA') return true;
    return el.tagName === 'INPUT' && TEXT_TYPES.test((el.getAttribute('type') || '').toLowerCase());
  }

  function labelOf(el) {
    var t = el.getAttribute('aria-label') || el.getAttribute('placeholder') || '';
    if (!t && el.labels && el.labels[0]) t = el.labels[0].textContent;
    if (!t && el.id) {
      var l = document.querySelector('label[for="' + el.id + '"]');
      if (l) t = l.textContent;
    }
    return (t || el.getAttribute('name') || '').trim().slice(0, 80);
  }

  function otpGroup(el) {
    if (el.tagName !== 'INPUT' || el.maxLength !== 1) return null;
    var scope = el.closest('form') || (el.parentElement && el.parentElement.parentElement) || document;
    var all = Array.prototype.filter.call(scope.querySelectorAll('input'), function (i) { return i.maxLength === 1 && shown(i); });
    return all.length >= 4 ? all : null;
  }

  function textAt(el) {
    if (el.tagName === 'LABEL' && el.control) el = el.control;
    if (isText(el)) return el;
    var inner = el.querySelectorAll ? el.querySelectorAll('input,textarea') : [];
    var r = el.getBoundingClientRect();
    if (inner.length === 1 && r.width < 900 && r.height < 160 && isText(inner[0])) return inner[0];
    var ce = el.closest && el.closest('[contenteditable=""],[contenteditable=true]');
    return ce && isText(ce) ? ce : null;
  }

  function textInfo(el) {
    var id = ++editSeq;
    el.setAttribute('data-tvedit', id);
    var group = otpGroup(el);
    var type = (el.getAttribute('type') || 'text').toLowerCase();
    var mode = (el.getAttribute('inputmode') || '').toLowerCase();
    var ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    return {
      t: 'text',
      id: id,
      kind: el.isContentEditable || el.tagName === 'TEXTAREA' ? 'multi' : 'line',
      type: group || ac === 'one-time-code' || mode === 'numeric' ? 'number' : type,
      value: group ? group.map(function (i) { return i.value; }).join('')
                   : el.isContentEditable ? el.innerText : (el.value || ''),
      label: labelOf(el),
      max: group ? group.length : (el.maxLength > 0 ? el.maxLength : 0)
    };
  }

  function setValue(el, val) {
    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, val); // React-safe
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function setText(id, val) {
    var el = document.querySelector('[data-tvedit="' + id + '"]');
    if (!el) return false;
    var group = otpGroup(el);
    if (group) {
      for (var i = 0; i < group.length; i++) setValue(group[i], val.charAt(i) || '');
      (group[Math.min(val.length, group.length) - 1] || group[0]).focus();
      return true;
    }
    el.focus();
    if (el.isContentEditable) {
      var range = document.createRange();
      range.selectNodeContents(el);
      var sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
      document.execCommand('insertText', false, val);
      return true;
    }
    setValue(el, val);
    return true;
  }

  /* ============================================================ clicks */
  // The video player box: the video and its overlay controls (not the whole page).
  function playerBox() {
    var v = video();
    if (!v || isFullscreen()) return null;
    var vr = v.getBoundingClientRect(), best = v;
    for (var p = v.parentElement; p && p !== document.body; p = p.parentElement) {
      var r = p.getBoundingClientRect();
      if (r.height <= vr.height * 1.6 + 80 && r.width <= vr.width * 1.15 + 24) best = p; else break;
    }
    return best;
  }

  function isFsControl(el) {
    var s = (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '') + ' ' + (el.getAttribute('data-purpose') || '');
    return /full\s?-?screen/i.test(s);
  }

  // Udemy's player ignores touch taps, so inside it we click like a mouse.
  function mouseClick(el, x, y) {
    if (x === undefined) {
      var r = el.getBoundingClientRect();
      x = r.left + r.width / 2;
      y = r.top + r.height / 2;
    }
    var base = { bubbles: true, cancelable: true, composed: true, view: window, clientX: x, clientY: y, button: 0 };
    function fire(type, buttons) {
      var o = {};
      for (var k in base) o[k] = base[k];
      o.buttons = buttons;
      var ev;
      if (/^pointer/.test(type) && window.PointerEvent) {
        o.pointerId = 1; o.pointerType = 'mouse'; o.isPrimary = true;
        ev = new PointerEvent(type, o);
      } else {
        o.detail = type === 'click' ? 1 : 0;
        ev = new MouseEvent(type, o);
      }
      el.dispatchEvent(ev);
    }
    fire('pointerover', 0); fire('mouseover', 0);
    fire('pointermove', 0); fire('mousemove', 0);
    fire('pointerdown', 1); fire('mousedown', 1);
    if (el.focus && el.matches && el.matches(CLICKABLE)) { try { el.focus({ preventScroll: true }); } catch (e) {} }
    fire('pointerup', 0); fire('mouseup', 0);
    fire('click', 0);
  }

  // What OK should do at a point (fractions of the viewport).
  function hit(fx, fy) {
    var x = fx * innerWidth, y = fy * innerHeight;
    var el = document.elementFromPoint(x, y);
    if (!el) return null;
    var t = textAt(el);
    if (t) return textInfo(t);
    var box = playerBox();
    if (box && box.contains(el)) {
      var target = (el.closest && el.closest(CLICKABLE)) || el;
      if (!box.contains(target)) target = el;
      if (isFsControl(target)) return { t: 'fs' };
      mouseClick(target, x, y);
      return { t: 'done' };
    }
    return null; // the app sends a real tap
  }

  /* ============================================================ scrolling */
  function scrollable(el, vertical, dir) {
    var cs = getComputedStyle(el);
    var ov = vertical ? cs.overflowY : cs.overflowX;
    if (!/(auto|scroll|overlay)/.test(ov)) return false;
    if (vertical) {
      if (el.scrollHeight <= el.clientHeight + 4) return false;
      return dir < 0 ? el.scrollTop > 1 : el.scrollTop + el.clientHeight < el.scrollHeight - 1;
    }
    if (el.scrollWidth <= el.clientWidth + 4) return false;
    return dir < 0 ? el.scrollLeft > 1 : el.scrollLeft + el.clientWidth < el.scrollWidth - 1;
  }

  function scrollerAt(x, y, vertical, dir) {
    var el = document.elementFromPoint(x, y);
    for (var p = el; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
      if (scrollable(p, vertical, dir)) return p;
    }
    return null;
  }

  // Find a scrollable panel under the pointer's column/row (skipping a header at the screen edge).
  function scrollPoint(fx, fy, vertical, dir) {
    var x = fx * innerWidth, y = fy * innerHeight;
    var n = 10;
    for (var k = 0; k <= n; k++) {
      var px = x, py = y;
      if (vertical) py = y < innerHeight / 2 ? y + k * innerHeight / (2 * n) : y - k * innerHeight / (2 * n);
      else px = x < innerWidth / 2 ? x + k * innerWidth / (2 * n) : x - k * innerWidth / (2 * n);
      var s = scrollerAt(Math.min(Math.max(px, 1), innerWidth - 2), Math.min(Math.max(py, 1), innerHeight - 2), vertical, dir);
      if (s) {
        var r = s.getBoundingClientRect();
        return {
          fx: Math.min(Math.max(px, r.left + 8), r.right - 8) / innerWidth,
          fy: Math.min(Math.max(py, r.top + 8), r.bottom - 8) / innerHeight
        };
      }
    }
    return null; // the page itself scrolls: use the pointer position
  }

  /* ============================================================ navigation mode */
  var NAV_SEL = [
    'a[href]', 'button', 'input:not([type=hidden])', 'select', 'textarea', 'summary', 'video',
    '[role=button]', '[role=link]', '[role=tab]', '[role=menuitem]', '[role=menuitemradio]',
    '[role=menuitemcheckbox]', '[role=option]', '[role=checkbox]', '[role=radio]', '[role=switch]',
    '[role=treeitem]', '[tabindex]:not([tabindex="-1"])', '[contenteditable=""]', '[contenteditable=true]',
    '[data-purpose^="curriculum-item"]'
  ].join(',');
  var FOCUS_CLS = '__tvf';
  var navOn = false;
  var cur = null;          // { el, ring }
  var saved = null;        // focus to restore when a dialog closes
  var lastHref = location.href;
  var cursorCache = new WeakMap();

  function inView(r) { return r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth; }

  function ringOf(el) {
    if (el.tagName === 'INPUT' && /^(checkbox|radio)$/i.test(el.type)) {
      var r = el.getBoundingClientRect();
      if (r.width < 4 || r.height < 4 || getComputedStyle(el).opacity === '0') {
        return el.closest('label') || (el.labels && el.labels[0]) || el.parentElement || el;
      }
    }
    return el;
  }

  function topDialog() {
    var ds = document.querySelectorAll('[role=dialog],[role=alertdialog],dialog[open],[aria-modal=true]');
    for (var i = ds.length - 1; i >= 0; i--) {
      var r = ds[i].getBoundingClientRect();
      if (r.width > 0 && r.height > 0 && getComputedStyle(ds[i]).visibility !== 'hidden') return ds[i];
    }
    return null;
  }

  // Udemy marks many clickable rows with data-purpose + cursor:pointer (no button/link).
  function extraClickables(scope) {
    var out = [];
    var els = scope.querySelectorAll('[data-purpose]');
    for (var i = 0; i < els.length && i < 800; i++) {
      var el = els[i];
      if (el.matches(NAV_SEL) || el.closest('a[href],button')) continue;
      var c = cursorCache.get(el);
      if (c === undefined) { c = getComputedStyle(el).cursor === 'pointer'; cursorCache.set(el, c); }
      if (c && !el.querySelector('a[href],button,[role=button]')) out.push(el);
    }
    return out;
  }

  function collect() {
    var scope = topDialog() || document;
    var nodes = Array.prototype.slice.call(scope.querySelectorAll(NAV_SEL)).concat(extraClickables(scope));
    var out = [], set = new Set();
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.disabled || set.has(el)) continue;
      if (el.closest('[inert],[aria-hidden=true]')) continue;
      var ring = ringOf(el);
      var r = ring.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) continue;
      var cs = getComputedStyle(ring);
      if (cs.visibility === 'hidden' || cs.display === 'none') continue;
      out.push({ el: el, ring: ring, r: r });
      set.add(el);
    }
    // Drop a focusable nested in another of (almost) the same size, e.g. a button filling a card link.
    return out.filter(function (it) {
      var p = it.el.parentElement && it.el.parentElement.closest(NAV_SEL);
      if (!p || !set.has(p)) return true;
      var pr = p.getBoundingClientRect();
      return (it.r.width * it.r.height) / Math.max(1, pr.width * pr.height) < 0.7;
    });
  }

  function clipped(it) {
    var r = it.r;
    for (var p = it.ring.parentElement, i = 0; p && i < 14; p = p.parentElement, i++) {
      var cs = getComputedStyle(p);
      var ov = cs.overflowX + ' ' + cs.overflowY;
      if (!/(hidden|clip|auto|scroll)/.test(ov)) continue;
      var pr = p.getBoundingClientRect();
      var outside = r.right <= pr.left + 1 || r.left >= pr.right - 1 || r.bottom <= pr.top + 1 || r.top >= pr.bottom - 1;
      if (!outside) continue;
      return !/(auto|scroll)/.test(ov);
    }
    return false;
  }

  function covered(it) {
    var r = it.r;
    if (!inView(r)) return false;
    var x = Math.min(Math.max((r.left + r.right) / 2, 1), innerWidth - 2);
    var y = Math.min(Math.max((r.top + r.bottom) / 2, 1), innerHeight - 2);
    var h = document.elementFromPoint(x, y);
    if (!h || it.ring.contains(h) || h.contains(it.ring) || it.el.contains(h)) return false;
    for (var p = h, i = 0; p && i < 16; p = p.parentElement, i++) {
      if (getComputedStyle(p).position === 'fixed') {
        var pr = p.getBoundingClientRect();
        return pr.width * pr.height > 0.5 * innerWidth * innerHeight;
      }
    }
    return false;
  }

  // Items scrolled out of sight inside a panel are only reachable from within that panel.
  function hiddenInPanel(it, fromPanel) {
    var s = scrollerOf(it.ring);
    if (!s || (fromPanel && fromPanel === s)) return false;
    var sr = s.getBoundingClientRect(), r = it.r;
    return r.bottom <= sr.top + 2 || r.top >= sr.bottom - 2 || r.right <= sr.left + 2 || r.left >= sr.right - 2;
  }

  function usable(it, fromPanel) { return !clipped(it) && !covered(it) && !hiddenInPanel(it, fromPanel); }

  // Inside a short fixed/sticky bar (site header, toolbar)? Tall sticky panels (lecture list) don't count.
  function pinned(el) {
    for (var p = el, i = 0; p && p !== document.body && i < 20; p = p.parentElement, i++) {
      var pos = getComputedStyle(p).position;
      if (pos === 'fixed' || pos === 'sticky') return p.getBoundingClientRect().height < innerHeight * 0.35;
    }
    return false;
  }

  function scrollerOf(el) {
    for (var p = el && el.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
      var ov = getComputedStyle(p).overflowY;
      if (/(auto|scroll|overlay)/.test(ov) && p.scrollHeight > p.clientHeight + 4) return p;
    }
    return null;
  }

  function pageCanScroll(dir) {
    var se = document.scrollingElement || document.documentElement;
    return dir > 0 ? se.scrollTop + innerHeight < se.scrollHeight - 4 : se.scrollTop > 4;
  }

  function setFocus(it) {
    css();
    if (cur) cur.ring.classList.remove(FOCUS_CLS);
    cur = it;
    it.ring.classList.add(FOCUS_CLS);
    var ae = document.activeElement;
    if (ae && ae !== it.el && isText(ae)) ae.blur();
    var r = it.ring.getBoundingClientRect();
    var sc = scrollerOf(it.ring);
    var top = 80, bottom = innerHeight - 40;
    if (sc) {
      var sr = sc.getBoundingClientRect();
      top = Math.max(top, sr.top + 10);
      bottom = Math.min(bottom, sr.bottom - 10);
    }
    if (r.top < top || r.bottom > bottom || r.left < 0 || r.right > innerWidth) {
      it.ring.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
    }
    if (playerBox() && playerBox().contains(it.el)) wake();
  }

  function clearFocus() {
    if (cur) cur.ring.classList.remove(FOCUS_CLS);
    cur = null;
  }

  function pickInitial(list) {
    var v = video();
    if (v) {
      for (var j = 0; j < list.length; j++) if (list[j].el === v && inView(list[j].r)) { setFocus(list[j]); return true; }
    }
    var best = null, bestScore = Infinity;
    for (var i = 0; i < list.length; i++) {
      var it = list[i], r = it.r;
      if (!inView(r)) continue;
      var score = (r.top < 70 ? 100000 : 0) + r.top * 3 + r.left;
      if (score < bestScore && usable(it)) { best = it; bestScore = score; }
    }
    if (!best && list.length) best = list[0];
    if (best) setFocus(best);
    return !!best;
  }

  function curValid() {
    if (location.href !== lastHref) { lastHref = location.href; clearFocus(); return false; }
    if (!cur || !document.contains(cur.el)) { cur = null; return false; }
    var r = cur.ring.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) { clearFocus(); return false; }
    var d = topDialog();
    if (d && !d.contains(cur.el)) { saved = cur; clearFocus(); return false; }
    return true;
  }

  function scrollBy(el, dir) {
    var amount = (el ? el.clientHeight : innerHeight) * 0.6 * dir;
    if (el) el.scrollBy({ top: amount, behavior: 'smooth' });
    else window.scrollBy({ top: amount, behavior: 'smooth' });
  }

  function navMove(dir) {
    css();
    var list = collect();
    if (!curValid()) { if (!pickInitial(list)) scrollBy(null, dir === 'up' ? -1 : 1); return ''; }

    var vertical = dir === 'up' || dir === 'down';
    var sign = dir === 'up' || dir === 'left' ? -1 : 1;
    var c = cur.ring.getBoundingClientRect();
    if (!vertical && !inView(c)) { clearFocus(); pickInitial(list); return ''; } // scrolled away: start from what's on screen
    var fromEdge = false;
    if (!inView(c) && !pinned(cur.ring) && vertical) {
      var edge = dir === 'up' ? innerHeight : 0;
      c = { top: edge, bottom: edge, left: c.left, right: c.right, width: c.width, height: 0 };
      fromEdge = true;
    }
    var ccx = (c.left + c.right) / 2, ccy = (c.top + c.bottom) / 2;
    var scored = [];
    for (var pass = 0; pass < 2 && !scored.length; pass++) {
      for (var i = 0; i < list.length; i++) {
        var t = list[i];
        if (t.el === cur.el || t.ring.contains(cur.ring) || cur.ring.contains(t.ring)) continue;
        var r = t.r, tcx = (r.left + r.right) / 2, tcy = (r.top + r.bottom) / 2;
        var primary, ortho, ok;
        if (!vertical) {
          ok = dir === 'right' ? (pass ? tcx > ccx + 1 : r.left >= c.right - 8) : (pass ? tcx < ccx - 1 : r.right <= c.left + 8);
          if (r.bottom <= c.top + 4 || r.top >= c.bottom - 4) ok = false; // stay on the same row
          primary = dir === 'right' ? r.left - c.right : c.left - r.right;
          ortho = Math.max(0, r.top - c.bottom, c.top - r.bottom) + Math.abs(tcy - ccy) * 0.1;
        } else {
          ok = dir === 'down' ? (pass ? tcy > ccy + 1 : r.top >= c.bottom - 8) : (pass ? tcy < ccy - 1 : r.bottom <= c.top + 8);
          if (pass && (r.right <= c.left || r.left >= c.right)) ok = false;
          primary = dir === 'down' ? r.top - c.bottom : c.top - r.bottom;
          ortho = Math.max(0, r.left - c.right, c.left - r.right) + Math.abs(tcx - ccx) * 0.1;
        }
        if (!ok) continue;
        var gap = vertical ? Math.max(0, r.left - c.right, c.left - r.right) : Math.max(0, r.top - c.bottom, c.top - r.bottom);
        scored.push({ it: t, s: Math.max(0, primary) + ortho * 3, p: Math.max(0, primary), g: gap });
      }
    }
    scored.sort(function (a, b) { return a.s - b.s; });

    if (vertical) {
      // Inside a scrolling panel (e.g. the lecture list): stay in it until it reaches its end.
      var sc = scrollerOf(cur.ring);
      if (sc) {
        var inside = scored.filter(function (x) { return sc.contains(x.it.el); });
        var canMore = sc.scrollTop > 1 && sign < 0 || sign > 0 && sc.scrollTop + sc.clientHeight < sc.scrollHeight - 1;
        for (var k = 0; k < inside.length && k < 25; k++) {
          if (usable(inside[k].it, sc)) {
            if (inside[k].p > sc.clientHeight * 0.9 && canMore) { scrollBy(sc, sign); return ''; }
            setFocus(inside[k].it);
            return '';
          }
        }
        if (canMore) { scrollBy(sc, sign); return ''; }
      }
    }

    var scrollablePage = vertical && pageCanScroll(sign);
    var panel = scrollerOf(cur.ring);
    var best = null;
    for (var j = 0; j < scored.length && j < 25; j++) {
      var cand = scored[j].it;
      if ((scrollablePage || fromEdge) && pinned(cand.ring) && !pinned(cur.ring)) continue; // keep the header for last
      if (usable(cand, panel)) { best = scored[j]; break; }
    }
    // Nothing close below/above in this column: scroll the page to read, don't jump across the screen.
    if (scrollablePage && (!best || best.p > innerHeight * 0.9 || best.g > innerWidth * 0.25)) { scrollBy(null, sign); return ''; }
    if (best) setFocus(best.it);
    else if (vertical) scrollBy(null, sign);
    return '';
  }

  // OK in Navigation mode.
  function navOk() {
    if (!curValid()) { pickInitial(collect()); return { t: 'done' }; }
    var el = cur.el;
    if (el.tagName === 'VIDEO') return { t: 'done', msg: playPause() };
    if (isText(el)) return textInfo(el);
    if (isFsControl(el)) return { t: 'fs' };
    var box = playerBox();
    if (box && box.contains(el)) { mouseClick(el); return { t: 'done' }; }
    var r = cur.ring.getBoundingClientRect();
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    if (inView(r)) {
      var h = document.elementFromPoint(x, y);
      if (h && (el.contains(h) || h.contains(el) || cur.ring.contains(h))) return { t: 'tap', fx: x / innerWidth, fy: y / innerHeight };
    }
    el.click();
    return { t: 'done' };
  }

  function esc() {
    var t = document.activeElement || document.body;
    ['keydown', 'keyup'].forEach(function (type) {
      t.dispatchEvent(new KeyboardEvent(type, { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
    });
  }

  // Back: close a popup / dialog first. Returns true if handled.
  function back() {
    var ae = document.activeElement;
    if (isText(ae)) { ae.blur(); return true; }
    var d = topDialog();
    if (d) {
      var btn = d.querySelector('[aria-label*="close" i],[data-purpose*="close" i],button[class*="close" i]');
      if (btn) mouseClick(btn); else esc();
      clearFocus();
      var s = saved; saved = null;
      if (s && navOn) setTimeout(function () { if (!cur && document.contains(s.el) && !topDialog()) setFocus(s); }, 150);
      return true;
    }
    var pop = document.querySelector('[aria-haspopup][aria-expanded="true"]');
    if (pop) { esc(); mouseClick(pop); return true; }
    return false;
  }

  function navEnable(on) {
    navOn = !!on;
    if (!navOn) clearFocus();
    else if (!curValid()) pickInitial(collect());
    return navOn;
  }

  // Center of the focused item (for scrolling around it).
  function navPoint() {
    if (!curValid()) return null;
    var r = cur.ring.getBoundingClientRect();
    if (!inView(r)) return null;
    return { fx: (r.left + r.width / 2) / innerWidth, fy: (r.top + r.height / 2) / innerHeight };
  }

  /* ============================================================ actions & state */
  function media(action) {
    var v = video();
    if (!v) return 'No video on this page';
    var msg;
    switch (action) {
      case 'playpause': msg = playPause(); break;
      case 'play': msg = playPause('play'); break;
      case 'pause': msg = playPause('pause'); break;
      case 'fwd5': msg = seek(5); break;
      case 'back5': msg = seek(-5); break;
      case 'fwd10': msg = seek(10); break;
      case 'back10': msg = seek(-10); break;
      case 'fwd30': msg = seek(30); break;
      case 'back30': msg = seek(-30); break;
      case 'faster': msg = speed(1); break;
      case 'slower': msg = speed(-1); break;
      case 'normal': msg = setSpeed(1); break;
      case 'next': msg = lecture(1); break;
      case 'prev': msg = lecture(-1); break;
      case 'captions': msg = captions(); break;
      case 'restart': v.currentTime = 0; msg = '↺  Restarted lecture'; break;
      case 'status': msg = (v.paused ? '⏸  ' : '▶  ') + pos(v) + '      ' + rateMsg(v.playbackRate); break;
      default: msg = '';
    }
    wake();
    return msg;
  }

  function state(nav) {
    if (nav !== undefined && !!nav !== navOn) navEnable(nav);
    hideSkipLinks();
    var v = video(), r = v && v.getBoundingClientRect();
    if (cssFs && v && !v.classList.contains('__tvv')) cssFullscreen(true); // lecture changed: keep fallback fullscreen
    if (navOn && !curValid()) pickInitial(collect());
    var fsEl = document.fullscreenElement;
    return {
      v: !!(v && ((r.width >= 320 && r.height >= 180) || fsEl)),
      fs: isFullscreen(),
      path: location.pathname
    };
  }

  window.__tv = {
    state: state, media: media, setSpeed: setSpeed, hit: hit, setText: setText, wake: wake,
    scrollPoint: scrollPoint, cssFullscreen: cssFullscreen, isFullscreen: isFullscreen, exitFullscreen: exitFullscreen,
    navEnable: navEnable, navMove: navMove, navOk: navOk, navPoint: navPoint, back: back,
    current: function () { return cur && cur.el; }
  };
})();
