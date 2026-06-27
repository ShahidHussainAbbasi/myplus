/*
 * Global AJAX waiting overlay — the single source for the whole application.
 *
 * Include THIS one file (no per-page markup or CSS). It self-injects a blocking spinner overlay
 * and ties it to jQuery's global AJAX lifecycle, so EVERY $.ajax/$.post/$.get shows the overlay
 * while the request is in flight and the user cannot interact until the server responds.
 *
 * jQuery fires `ajaxStart` when the first request begins and `ajaxStop` when the last one finishes,
 * so concurrent requests are coalesced into a single overlay. A short show-delay keeps quick calls
 * from flashing the overlay (only requests slower than SHOW_DELAY_MS surface it).
 */
(function () {
  if (window.__appAjaxOverlayInstalled) return;
  window.__appAjaxOverlayInstalled = true;

  var SHOW_DELAY_MS = 220;
  var showTimer = null;

  function injectStyles() {
    if (document.getElementById('app-ajax-overlay-style')) return;
    var css =
      '#appAjaxOverlay{position:fixed;inset:0;z-index:99999;display:none;align-items:center;justify-content:center;' +
      'background:rgba(13,35,83,.45);backdrop-filter:blur(2px);-webkit-backdrop-filter:blur(2px)}' +
      '#appAjaxOverlay.show{display:flex}' +
      '#appAjaxOverlay .ao-box{background:#fff;border-radius:14px;padding:22px 28px;text-align:center;' +
      'box-shadow:0 20px 50px rgba(13,35,83,.35);min-width:130px}' +
      '#appAjaxOverlay .ao-spin{width:38px;height:38px;margin:0 auto;border-radius:50%;' +
      'border:4px solid #e2e8f0;border-top-color:#1565C0;animation:aoSpin .8s linear infinite}' +
      '#appAjaxOverlay .ao-msg{margin-top:12px;font-family:Inter,system-ui,sans-serif;font-size:13px;' +
      'font-weight:600;color:#0f172a}' +
      '@keyframes aoSpin{to{transform:rotate(360deg)}}';
    var s = document.createElement('style');
    s.id = 'app-ajax-overlay-style';
    s.appendChild(document.createTextNode(css));
    document.head.appendChild(s);
  }

  function injectDom() {
    if (document.getElementById('appAjaxOverlay')) return;
    var d = document.createElement('div');
    d.id = 'appAjaxOverlay';
    d.setAttribute('aria-hidden', 'true');
    d.setAttribute('role', 'status');
    d.innerHTML = '<div class="ao-box"><div class="ao-spin"></div><div class="ao-msg">Please wait…</div></div>';
    document.body.appendChild(d);
  }

  function show() {
    var el = document.getElementById('appAjaxOverlay');
    if (el) { el.classList.add('show'); el.setAttribute('aria-hidden', 'false'); }
  }

  function hide() {
    if (showTimer) { clearTimeout(showTimer); showTimer = null; }
    var el = document.getElementById('appAjaxOverlay');
    if (el) { el.classList.remove('show'); el.setAttribute('aria-hidden', 'true'); }
  }

  function ready(fn) {
    if (document.readyState !== 'loading') fn();
    else document.addEventListener('DOMContentLoaded', fn);
  }

  ready(function () {
    if (!window.jQuery) return; // requires jQuery's global ajax events
    injectStyles();
    injectDom();
    var $ = window.jQuery;
    $(document).ajaxStart(function () {
      if (showTimer) clearTimeout(showTimer);
      showTimer = setTimeout(show, SHOW_DELAY_MS);
    });
    $(document).ajaxStop(hide);
    $(document).ajaxError(hide); // belt-and-suspenders: never strand the overlay on an error
  });
})();
