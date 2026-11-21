/*
 * Udemy TV - remote-control (D-pad) navigation layer.
 * Injected into every page. Turns arrow keys into spatial focus movement,
 * OK into click, and adds video controls. No mouse cursor needed.
 */
(function () {
  if (window.__tv) return;

  var FOCUS_CLS = '__tvf';
  var SEL = [
    'a[href]', 'button', 'input:not([type=hidden])', 'select', 'textarea', 'summary', 'video',
    '[role=button]', '[role=link]', '[role=tab]', '[role=menuitem]', '[role=menuitemradio]',
    '[role=menuitemcheckbox]', '[role=option]', '[role=checkbox]', '[role=radio]', '[role=switch]',
    '[role=treeitem]', '[tabindex]:not([tabindex="-1"])', '[contenteditable=""]', '[contenteditable=true]'
  ].join(',');
  var DIRS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
  var TEXT_TYPES = /^(text|search|email|password|number|url|tel|)$/;

  var cur = null;          // { el, ring }
  var saved = null;        // focus to restore when a dialog closes
  var lastHref = location.href;
  var toastTimer = null;

  /* ---------- styles & toast ---------- */
  function css() {
    if (document.getElementById('__tvcss')) return;
    var s = document.createElement('style');
    s.id = '__tvcss';
    s.textContent =
      '.' + FOCUS_CLS + '{outline:4px solid #ffcc00!important;outline-offset:2px!important;' +
      'box-shadow:0 0 0 8px rgba(255,204,0,.35)!important;border-radius:4px;}' +
      '#__tvtoast{position:fixed;left:50%;bottom:9%;transform:translateX(-50%);background:rgba(0,0,0,.82);' +
      'color:#fff;font:600 24px/1.3 sans-serif;padding:12px 26px;border-radius:12px;z-index:2147483647;' +
      'pointer-events:none;transition:opacity .25s;opacity:0}';
    (document.head || document.documentElement).appendChild(s);
  }

  function toast(msg) {
    css();
    var t = document.getElementById('__tvtoast');
    if (!t) {
      t = document.createElement('div');
      t.id = '__tvtoast';
      (document.body || document.documentElement).appendChild(t);
    }
    t.textContent = msg;
    t.style.opacity = '1';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.style.opacity = '0'; }, 1400);
  }

  /* ---------- helpers ---------- */
  function isText(el) {
    if (!el || el === document.body) return false;
    if (el.isContentEditable) return true;
    if (el.tagName === 'TEXTAREA') return true;
    return el.tagName === 'INPUT' && TEXT_TYPES.test((el.getAttribute('type') || '').toLowerCase());
  }

  function caret(el) {
    try { return { s: el.selectionStart, e: el.selectionEnd, n: (el.value || '').length }; }
    catch (e) { return null; }
  }

  function inView(r) {
    return r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth;
  }

  // The element that gets the highlight. Custom checkboxes/radios often hide the
  // real <input>, so highlight its label instead.
  function ringOf(el) {
    if (el.tagName === 'INPUT' && /^(checkbox|radio)$/i.test(el.type)) {
      var r = el.getBoundingClientRect();
      var hidden = r.width < 4 || r.height < 4 || getComputedStyle(el).opacity === '0';
      if (hidden) return el.closest('label') || (el.labels && el.labels[0]) || el.parentElement || el;
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

  function collect() {
    var scope = topDialog() || document;
    var nodes = scope.querySelectorAll(SEL);
    var out = [], set = new Set();
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.disabled || el.id === '__tvtoast') continue;
      if (el.closest('[inert],[aria-hidden=true]')) continue;
      var ring = ringOf(el);
      var r = ring.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) continue;
      var cs = getComputedStyle(ring);
      if (cs.visibility === 'hidden' || cs.display === 'none') continue;
      out.push({ el: el, ring: ring, r: r });
      set.add(el);
    }
    // Drop focusables nested inside another focusable of (almost) the same size,
    // e.g. a <button> filling a whole card link.
    return out.filter(function (it) {
      var p = it.el.parentElement && it.el.parentElement.closest(SEL);
      if (!p || !set.has(p)) return true;
      var pr = p.getBoundingClientRect();
      return (it.r.width * it.r.height) / Math.max(1, pr.width * pr.height) < 0.7;
    });
  }

  // Hidden by an overflow:hidden ancestor (e.g. carousel items off to the side)?
  function clipped(it) {
    var r = it.r;
    for (var p = it.ring.parentElement, i = 0; p && i < 14; p = p.parentElement, i++) {
      var cs = getComputedStyle(p);
      var ov = cs.overflowX + ' ' + cs.overflowY;
      if (!/(hidden|clip|auto|scroll)/.test(ov)) continue;
      var pr = p.getBoundingClientRect();
      var outside = r.right <= pr.left + 1 || r.left >= pr.right - 1 || r.bottom <= pr.top + 1 || r.top >= pr.bottom - 1;
      if (!outside) continue;
      return !/(auto|scroll)/.test(ov); // reachable if the container can scroll
    }
    return false;
  }

  // Covered by a big fixed overlay (backdrop, drawer)? Sticky headers don't count.
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

  function usable(it) { return !clipped(it) && !covered(it); }

  // Inside a fixed/sticky bar (site header, player toolbar)?
  function pinned(el) {
    for (var p = el, i = 0; p && p !== document.body && i < 20; p = p.parentElement, i++) {
      var pos = getComputedStyle(p).position;
      if (pos === 'fixed' || pos === 'sticky') return true;
    }
    return false;
  }

  /* ---------- focus ---------- */
  function setFocus(it) {
    css();
    if (cur) cur.ring.classList.remove(FOCUS_CLS);
    cur = it;
    it.ring.classList.add(FOCUS_CLS);
    var ae = document.activeElement;
    if (ae && ae !== it.el && isText(ae)) ae.blur();
    // Don't focus text fields while just passing over them (would pop the keyboard).
    if (!isText(it.el)) { try { it.el.focus({ preventScroll: true }); } catch (e) {} }
    var r = it.ring.getBoundingClientRect();
    if (r.top < 80 || r.bottom > innerHeight - 40 || r.left < 0 || r.right > innerWidth) {
      it.ring.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
    }
  }

  function pickInitial(list) {
    var best = null, bestScore = Infinity;
    for (var i = 0; i < list.length; i++) {
      var it = list[i], r = it.r;
      if (!inView(r)) continue;
      // Prefer the main content below the site header, in reading order.
      var score = (r.top < 70 ? 100000 : 0) + r.top * 3 + r.left;
      if (score < bestScore && usable(it)) { best = it; bestScore = score; }
    }
    if (!best && list.length) best = list[0];
    if (best) setFocus(best);
    return !!best;
  }

  function curValid() {
    if (location.href !== lastHref) { lastHref = location.href; cur = null; return false; }
    if (!cur || !document.contains(cur.el)) { cur = null; return false; }
    var r = cur.ring.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) { cur = null; return false; }
    var d = topDialog();
    if (d && !d.contains(cur.el)) { cur.ring.classList.remove(FOCUS_CLS); saved = cur; cur = null; return false; }
    return true;
  }

  function scrollerFor(el, vertical) {
    for (var p = el && el.parentElement; p && p !== document.body; p = p.parentElement) {
      var cs = getComputedStyle(p);
      var ov = vertical ? cs.overflowY : cs.overflowX;
      if (/(auto|scroll)/.test(ov) && (vertical ? p.scrollHeight > p.clientHeight + 4 : p.scrollWidth > p.clientWidth + 4)) return p;
    }
    return null;
  }

  function scrollPage(dir) {
    var vertical = dir === 'up' || dir === 'down';
    var sign = dir === 'up' || dir === 'left' ? -1 : 1;
    var target = (cur && scrollerFor(cur.ring, vertical)) || null;
    if (!target && topDialog()) return; // never scroll the page behind an open dialog
    var amt = (vertical ? innerHeight : innerWidth) * 0.6 * sign;
    var opt = { top: vertical ? amt : 0, left: vertical ? 0 : amt, behavior: 'smooth' };
    if (target) target.scrollBy(opt); else window.scrollBy(opt);
  }

  function canScroll(dir) {
    var se = document.scrollingElement || document.documentElement;
    if (dir === 'down') return se.scrollTop + innerHeight < se.scrollHeight - 4;
    if (dir === 'up') return se.scrollTop > 4;
    return false;
  }

  function move(dir) {
    var list = collect();
    if (!curValid()) { if (!pickInitial(list)) scrollPage(dir); return; }
    var c = cur.ring.getBoundingClientRect();
    var fromEdge = false;
    // Focused item scrolled out of view (user was reading): continue from the screen edge.
    if (!inView(c) && !pinned(cur.ring)) {
      fromEdge = dir === 'up' || dir === 'down';
      var edge = dir === 'up' ? innerHeight : dir === 'down' ? 0 : null;
      if (edge !== null) c = { top: edge, bottom: edge, left: c.left, right: c.right, width: c.width, height: 0 };
    }
    var ccx = (c.left + c.right) / 2, ccy = (c.top + c.bottom) / 2;
    var scored = [];

    for (var pass = 0; pass < 2 && !scored.length; pass++) {
      for (var i = 0; i < list.length; i++) {
        var t = list[i];
        if (t.el === cur.el || t.ring.contains(cur.ring) || cur.ring.contains(t.ring)) continue;
        var r = t.r, tcx = (r.left + r.right) / 2, tcy = (r.top + r.bottom) / 2;
        var primary, ortho, ok;
        if (dir === 'right' || dir === 'left') {
          ok = dir === 'right'
            ? (pass ? tcx > ccx + 1 : r.left >= c.right - 8)
            : (pass ? tcx < ccx - 1 : r.right <= c.left + 8);
          // Stay on the same row (like the YouTube app): no jumping to other rows.
          if (r.bottom <= c.top + 4 || r.top >= c.bottom - 4) ok = false;
          primary = dir === 'right' ? r.left - c.right : c.left - r.right;
          ortho = Math.max(0, r.top - c.bottom, c.top - r.bottom);
          ortho += Math.abs(tcy - ccy) * 0.1;
        } else {
          ok = dir === 'down'
            ? (pass ? tcy > ccy + 1 : r.top >= c.bottom - 8)
            : (pass ? tcy < ccy - 1 : r.bottom <= c.top + 8);
          primary = dir === 'down' ? r.top - c.bottom : c.top - r.bottom;
          ortho = Math.max(0, r.left - c.right, c.left - r.right);
          ortho += Math.abs(tcx - ccx) * 0.1;
        }
        // Second pass only accepts overlapping neighbours on the same row/column,
        // so "right" at the end of a row never jumps down to another row.
        if (ok && pass && (dir === 'up' || dir === 'down') && (r.right <= c.left || r.left >= c.right)) ok = false;
        if (!ok) continue;
        scored.push({ it: t, s: Math.max(0, primary) + ortho * 3, p: Math.max(0, primary) });
      }
    }
    scored.sort(function (a, b) { return a.s - b.s; });

    var vertical = dir === 'up' || dir === 'down';
    var scrollable = vertical && canScroll(dir);
    var best = null;
    for (var j = 0; j < scored.length && j < 25; j++) {
      var cand = scored[j].it;
      // While the page can still scroll, don't let a sticky header steal focus.
      if ((scrollable || fromEdge) && pinned(cand.ring) && !pinned(cur.ring)) continue;
      if (usable(cand)) { best = scored[j]; break; }
    }
    // Long text with nothing focusable nearby: scroll to read instead of jumping far.
    if (scrollable && (!best || best.p > innerHeight * 0.9)) {
      scrollPage(dir);
      return;
    }
    if (best) setFocus(best.it); else scrollPage(dir);
  }

  /* ---------- video ---------- */
  function video() {
    var vs = Array.prototype.filter.call(document.querySelectorAll('video'), function (v) {
      return v.getBoundingClientRect().width > 50;
    });
    for (var i = 0; i < vs.length; i++) if (!vs[i].paused) return vs[i];
    return vs[0] || null;
  }

  function fmt(s) {
    if (!isFinite(s)) return '--:--';
    s = Math.max(0, Math.floor(s));
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
    return (h ? h + ':' + (m < 10 ? '0' : '') : '') + m + ':' + (x < 10 ? '0' : '') + x;
  }

  function wake(v) {
    // Udemy's player hides its controls until the mouse moves.
    v = v || video();
    if (!v) return;
    var r = v.getBoundingClientRect();
    var ev = { bubbles: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
    (v.parentElement || v).dispatchEvent(new MouseEvent('mousemove', ev));
    v.dispatchEvent(new MouseEvent('mousemove', ev));
  }

  function seek(sec) {
    var v = video();
    if (!v) return false;
    var d = isFinite(v.duration) ? v.duration : Infinity;
    v.currentTime = Math.min(Math.max(0, v.currentTime + sec), d - 0.5);
    toast((sec > 0 ? '⏩ +' : '⏪ ') + sec + 's   ' + fmt(v.currentTime) + ' / ' + fmt(v.duration));
    wake(v);
    return true;
  }

  function playPause(force) {
    var v = video();
    if (!v) return false;
    var play = force === 'play' ? true : force === 'pause' ? false : v.paused;
    if (play) { var p = v.play(); if (p && p.catch) p.catch(function () {}); toast('▶ Play'); }
    else { v.pause(); toast('⏸ Pause   ' + fmt(v.currentTime) + ' / ' + fmt(v.duration)); }
    wake(v);
    return true;
  }

  /* ---------- activate (OK button) ---------- */
  function activate() {
    if (!curValid()) { pickInitial(collect()); return; }
    var el = cur.el;
    if (el.tagName === 'VIDEO') { playPause(); return; }
    if (isText(el)) {
      el.focus();
      try { if (window.TvBridge) TvBridge.showKeyboard(); } catch (e) {}
      return;
    }
    if (el.tagName === 'SELECT') {
      el.focus();
      try { el.showPicker(); } catch (e) {}
      return;
    }
    el.click();
  }

  /* ---------- key handling ---------- */
  window.addEventListener('keydown', function (e) {
    var dir = DIRS[e.key];
    if (!dir && e.key !== 'Enter') return;
    var ae = document.activeElement;

    // Typing in a text box: let arrows move the caret until it hits the edge.
    if (isText(ae)) {
      if (e.key === 'Enter') return; // submit search etc.
      if (dir === 'left' || dir === 'right') {
        var c = caret(ae);
        if (c && dir === 'left' && c.s > 0) return;
        if (c && dir === 'right' && c.e < c.n) return;
      }
      if (ae.tagName === 'TEXTAREA' && (dir === 'up' || dir === 'down')) {
        var c2 = caret(ae);
        if (c2 && dir === 'up' && c2.s > 0) return;
        if (c2 && dir === 'down' && c2.e < c2.n) return;
      }
    }

    e.preventDefault();
    e.stopImmediatePropagation();

    // Focus on the video itself: left/right seek, OK play/pause.
    if (cur && cur.el.tagName === 'VIDEO' && document.contains(cur.el)) {
      if (dir === 'left') { seek(-10); return; }
      if (dir === 'right') { seek(10); return; }
      if (!dir) { playPause(); return; }
    }
    if (video()) wake();

    if (dir) move(dir); else activate();
  }, true);

  /* ---------- back button (called from the app) ---------- */
  function esc() {
    var t = document.activeElement || document.body;
    ['keydown', 'keyup'].forEach(function (type) {
      t.dispatchEvent(new KeyboardEvent(type, { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
    });
  }

  function back() {
    if (document.fullscreenElement || document.webkitFullscreenElement) {
      (document.exitFullscreen || document.webkitExitFullscreen).call(document);
      return true;
    }
    var ae = document.activeElement;
    if (isText(ae)) { ae.blur(); return true; }
    var d = topDialog();
    if (d) {
      var btn = d.querySelector('[aria-label*="close" i],[data-purpose*="close" i],button[class*="close" i]');
      if (btn) btn.click(); else esc();
      if (cur) cur.ring.classList.remove(FOCUS_CLS);
      cur = null;
      var s = saved; saved = null;
      if (s) setTimeout(function () {
        if (!cur && document.contains(s.el) && !topDialog()) setFocus(s);
      }, 120);
      return true;
    }
    var pop = document.querySelector('[aria-haspopup][aria-expanded="true"]');
    if (pop) { esc(); pop.click(); return true; }
    return false;
  }

  function media(action) {
    switch (action) {
      case 'playpause': return playPause();
      case 'play': return playPause('play');
      case 'pause': return playPause('pause');
      case 'rew': return seek(-10);
      case 'ff': return seek(10);
      case 'wake': wake(); return true;
    }
    return false;
  }

  window.__tv = { back: back, media: media, move: move, activate: activate, current: function () { return cur && cur.el; } };
})();
