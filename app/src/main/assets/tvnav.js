/*
 * Udemy TV - page helper injected into every page by the app.
 * The app handles all remote keys natively and calls into window.__tv:
 *   state()            -> { v: big video on page, fs: fullscreen, path }
 *   media(action)      -> message for the on-screen display
 *   hit(fx, fy)        -> text field under the pointer (for the native input box) or null
 *   setText(id, value) -> fill that field (React-safe)
 *   fsButton()         -> position of the player's fullscreen button (fractions of the viewport)
 * It also fits pages to the TV (desktop layout width) and hides the "Skip to content" link.
 */
(function () {
  if (window.__tv) return;

  var RATES = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
  var TEXT_TYPES = /^(text|search|email|password|number|url|tel|)$/;
  var cssFs = false;
  var editSeq = 0;
  var savedTracks = null;

  /* ================= fit to TV ================= */
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

  /* ================= styles ================= */
  function css() {
    if (document.getElementById('__tvcss') || !document.documentElement) return;
    var s = document.createElement('style');
    s.id = '__tvcss';
    s.textContent =
      // accessibility "Skip to content" links are only useful with a keyboard
      'a[href="#main-content-anchor"],[data-purpose="skip-to-content"],[class*="skip-to-main"],[class*="skip-link"]' +
      '{display:none!important}' +
      // fallback fullscreen: pin the player box over the whole screen
      'html.__tvfs,html.__tvfs body{overflow:hidden!important}' +
      'html.__tvfs .__tvbox{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;' +
      'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;' +
      'padding:0!important;z-index:2147483646!important;background:#000!important;transform:none!important}' +
      'html.__tvfs .__tvbox video{width:100%!important;height:100%!important;object-fit:contain!important}';
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

  /* ================= video ================= */
  function video() {
    var vs = document.querySelectorAll('video'), best = null, area = 0;
    for (var i = 0; i < vs.length; i++) {
      var r = vs[i].getBoundingClientRect();
      if (r.width * r.height > area) { best = vs[i]; area = r.width * r.height; }
    }
    return area > 0 ? best : null;
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
    return (sec > 0 ? '⏩  +' : '⏪  ') + sec + ' s      ' + pos(v);
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
    btn.click();
    return dir > 0 ? 'Next lecture  ⏭' : '⏮  Previous lecture';
  }

  // Captions: use the video's text tracks; otherwise open Udemy's captions menu.
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
      if (savedTracks && savedTracks.length === tt.length &&
          savedTracks.some(function (m) { return m !== 'disabled'; })) {
        for (i = 0; i < tt.length; i++) tt[i].mode = savedTracks[i];
      } else {
        var pick = 0;
        for (i = 0; i < tt.length; i++) if (/^en/i.test(tt[i].language || '') || /english/i.test(tt[i].label || '')) { pick = i; break; }
        tt[pick].mode = 'showing';
      }
      return 'Captions on';
    }
    var btn = first(['[data-purpose*="captions" i]', '[aria-label*="caption" i]', '[aria-label*="subtitle" i]']);
    if (btn) { wake(); btn.click(); return 'MENU:Captions menu opened — choose with the pointer'; }
    return 'No captions for this lecture';
  }

  /* ================= fullscreen ================= */
  function fsButton() {
    wake();
    var b = first(['[data-purpose*="fullscreen" i]', '[aria-label*="full screen" i]', '[aria-label*="fullscreen" i]',
                   '[title*="full screen" i]', '[title*="fullscreen" i]'], true);
    if (!b) return null;
    var r = b.getBoundingClientRect();
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    if (x < 0 || y < 0 || x > innerWidth || y > innerHeight) return null;
    var h = document.elementFromPoint(x, y);
    if (!h || !(b === h || b.contains(h) || h.contains(b))) return null; // hidden under something: don't tap blindly
    return { fx: x / innerWidth, fy: y / innerHeight };
  }

  function playerBox(v) {
    var vr = v.getBoundingClientRect(), best = v;
    for (var p = v.parentElement; p && p !== document.body; p = p.parentElement) {
      var r = p.getBoundingClientRect();
      if (r.width <= vr.width * 1.08 + 4 && r.height <= vr.height * 1.3 + 4) best = p; else break;
    }
    return best;
  }

  function cssFullscreen(on) {
    css();
    var old = document.querySelectorAll('.__tvbox');
    for (var i = 0; i < old.length; i++) old[i].classList.remove('__tvbox');
    var v = video();
    if (on && v) {
      playerBox(v).classList.add('__tvbox');
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

  /* ================= actions for the remote ================= */
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

  /* ================= native text input support ================= */
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

  function hit(fx, fy) {
    var el = document.elementFromPoint(fx * innerWidth, fy * innerHeight);
    if (!el) return null;
    if (el.tagName === 'LABEL' && el.control) el = el.control;
    if (!isText(el)) {
      // tapped the styled box around an input
      var inner = el.querySelectorAll ? el.querySelectorAll('input,textarea') : [];
      var r = el.getBoundingClientRect();
      if (inner.length === 1 && r.width < 900 && r.height < 160 && isText(inner[0])) el = inner[0];
      else {
        var ce = el.closest && el.closest('[contenteditable=""],[contenteditable=true]');
        if (ce && isText(ce)) el = ce; else return null;
      }
    }
    var id = ++editSeq;
    el.setAttribute('data-tvedit', id);
    var group = otpGroup(el);
    var type = (el.getAttribute('type') || 'text').toLowerCase();
    var mode = (el.getAttribute('inputmode') || '').toLowerCase();
    var ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    return {
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

  /* ================= state for the app ================= */
  function state() {
    hideSkipLinks();
    var v = video(), r = v && v.getBoundingClientRect();
    if (cssFs && v) { // keep the CSS fullscreen on the current player (lecture changed)
      var box = document.querySelector('.__tvbox');
      if (!box || !box.contains(v)) cssFullscreen(true);
    }
    return { v: !!(v && r.width >= 320 && r.height >= 180), fs: isFullscreen(), path: location.pathname };
  }

  window.__tv = {
    state: state, media: media, setSpeed: setSpeed, hit: hit, setText: setText, wake: wake,
    fsButton: fsButton, cssFullscreen: cssFullscreen, isFullscreen: isFullscreen, exitFullscreen: exitFullscreen
  };
})();
