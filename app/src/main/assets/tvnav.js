/*
 * Udemy TV - page helper injected into every page.
 *  - Fits pages to the TV by forcing a desktop layout width (window.__tvWidth, set by the app).
 *  - Video helpers used by "video mode": play/pause, seek, speed, next/previous lecture, fullscreen.
 *  - Reports page state to the app so it can switch between pointer mode and video mode.
 * Remote keys are handled natively by the app; this script never listens to keys.
 */
(function () {
  if (window.__tv) return;

  var RATES = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
  var toastTimer = null;
  var cssFs = false;

  /* ---------- fit to TV: desktop layout width, scaled to the screen ---------- */
  function applyViewport() {
    var w = window.__tvWidth;
    if (!w || window.top !== window) return;
    var head = document.head;
    if (!head) return;
    var m = head.querySelector('meta[name=viewport]');
    var content = 'width=' + w;
    if (!m) {
      m = document.createElement('meta');
      m.name = 'viewport';
      head.appendChild(m);
    }
    if (m.getAttribute('content') !== content) m.setAttribute('content', content);
  }

  function watchViewport() {
    applyViewport();
    if (!document.head) return;
    new MutationObserver(applyViewport).observe(document.head, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['content']
    });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', watchViewport);
  else watchViewport();

  /* ---------- styles & toast ---------- */
  function css() {
    if (document.getElementById('__tvcss')) return;
    var s = document.createElement('style');
    s.id = '__tvcss';
    s.textContent =
      '#__tvtoast{position:fixed;left:50%;bottom:10%;transform:translateX(-50%);background:rgba(0,0,0,.85);' +
      'color:#fff;font:600 26px/1.3 sans-serif;padding:14px 30px;border-radius:12px;z-index:2147483647;' +
      'pointer-events:none;transition:opacity .25s;opacity:0;white-space:nowrap}' +
      'html.__tvfs,html.__tvfs body{overflow:hidden!important}' +
      'html.__tvfs video.__tvv{position:fixed!important;left:0!important;top:0!important;width:100vw!important;' +
      'height:100vh!important;max-width:none!important;max-height:none!important;z-index:2147483646!important;' +
      'background:#000!important;object-fit:contain!important;transform:none!important}';
    (document.head || document.documentElement).appendChild(s);
  }

  function toast(msg) {
    css();
    var host = document.fullscreenElement || document.body || document.documentElement;
    var t = document.getElementById('__tvtoast');
    if (!t || t.parentNode !== host) {
      if (t) t.remove();
      t = document.createElement('div');
      t.id = '__tvtoast';
      host.appendChild(t);
    }
    t.textContent = msg;
    t.style.opacity = '1';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.style.opacity = '0'; }, 1500);
  }

  /* ---------- video ---------- */
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

  // Udemy's player hides its controls until the mouse moves.
  function wake(v) {
    v = v || video();
    if (!v) return;
    var r = v.getBoundingClientRect();
    var ev = { bubbles: true, clientX: r.left + r.width / 2, clientY: r.top + r.height * 0.6 };
    v.dispatchEvent(new MouseEvent('mousemove', ev));
    if (v.parentElement) v.parentElement.dispatchEvent(new MouseEvent('mousemove', ev));
  }

  function seek(sec) {
    var v = video();
    if (!v) return false;
    var d = isFinite(v.duration) ? v.duration : Infinity;
    v.currentTime = Math.min(Math.max(0, v.currentTime + sec), d - 0.5);
    toast((sec > 0 ? '⏩ +' : '⏪ ') + sec + 's    ' + fmt(v.currentTime) + ' / ' + fmt(v.duration));
    wake(v);
    return true;
  }

  function playPause(force) {
    var v = video();
    if (!v) return false;
    var play = force === 'play' ? true : force === 'pause' ? false : v.paused;
    if (play) {
      var p = v.play();
      if (p && p.catch) p.catch(function () {});
      toast('▶  Play');
    } else {
      v.pause();
      toast('⏸  Paused    ' + fmt(v.currentTime) + ' / ' + fmt(v.duration));
    }
    wake(v);
    return true;
  }

  function setSpeed(r) {
    var v = video();
    if (!v) return false;
    v.playbackRate = r;
    toast('Speed  ' + r + '×');
    wake(v);
    return true;
  }

  function speed(step) {
    var v = video();
    if (!v) return false;
    var cur = v.playbackRate, idx = 0, diff = Infinity;
    for (var i = 0; i < RATES.length; i++) {
      var d = Math.abs(RATES[i] - cur);
      if (d < diff) { diff = d; idx = i; }
    }
    idx = Math.min(RATES.length - 1, Math.max(0, idx + step));
    return setSpeed(RATES[idx]);
  }

  function visible(el) {
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  function clickFirst(sels) {
    for (var i = 0; i < sels.length; i++) {
      var els = document.querySelectorAll(sels[i]);
      for (var j = 0; j < els.length; j++) {
        if (visible(els[j]) || els[j].getAttribute('data-purpose')) { els[j].click(); return true; }
      }
    }
    return false;
  }

  function lecture(dir) {
    var ok = dir > 0
      ? clickFirst(['[data-purpose="go-to-next"]', '#go-to-next-item', '[aria-label*="next lecture" i]',
                    '[aria-label^="Next" i]'])
      : clickFirst(['[data-purpose="go-to-previous"]', '#go-to-previous-item', '[aria-label*="previous lecture" i]',
                    '[aria-label^="Previous" i]']);
    toast(ok ? (dir > 0 ? 'Next lecture ⏭' : '⏮ Previous lecture') : 'No ' + (dir > 0 ? 'next' : 'previous') + ' lecture found');
    return ok;
  }

  // Fallback fullscreen: stretch the <video> over the whole page with CSS.
  function cssFullscreen(on) {
    css();
    var v = video();
    if (on === undefined) on = !cssFs;
    var old = document.querySelectorAll('video.__tvv');
    for (var i = 0; i < old.length; i++) old[i].classList.remove('__tvv');
    if (on && v) {
      v.classList.add('__tvv');
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

  function media(action) {
    switch (action) {
      case 'playpause': return playPause();
      case 'play': return playPause('play');
      case 'pause': return playPause('pause');
      case 'rew': return seek(-10);
      case 'ff': return seek(10);
      case 'faster': return speed(1);
      case 'slower': return speed(-1);
      case 'next': return lecture(1);
      case 'prev': return lecture(-1);
      case 'wake': wake(); return true;
    }
    return false;
  }

  // Polled by the app about once a second.
  function state() {
    var v = video(), r = v && v.getBoundingClientRect();
    return {
      player: !!(v && r.width >= 320 && r.height >= 180),
      playing: !!(v && !v.paused),
      rate: v ? v.playbackRate : 1,
      fs: isFullscreen(),
      path: location.pathname
    };
  }

  window.__tv = {
    media: media, setSpeed: setSpeed, state: state, toast: toast,
    cssFullscreen: cssFullscreen, isFullscreen: isFullscreen, exitFullscreen: exitFullscreen, wake: wake
  };
})();
