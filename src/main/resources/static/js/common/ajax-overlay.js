/*
 * Global AJAX waiting overlay — the single source for the whole application.
 *
 * Include THIS one file (no per-page markup or CSS). It self-injects a blocking spinner overlay
 * and ties it to jQuery's global AJAX lifecycle, so a READ shows the overlay while it is in flight.
 * A caller that should not block the screen passes `nonBlocking: true` in its $.ajax settings — see
 * the note on ajaxSend below for why that flag exists rather than `global: false`.
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
    /*
     * ⭐ PERF-13 — A REQUEST CAN OPT OUT OF THE OVERLAY WITHOUT LEAVING THE GLOBAL EVENTS.
     *
     * This used ajaxStart/ajaxStop, which say only "something is in flight" — there is no way to ask WHICH
     * request, so every call blocked the screen. Saving a customer covered the whole viewport while the user
     * waited for a write that takes 80 ms and almost always succeeds.
     *
     * ⚠ `global: false` is NOT the answer, and this is the trap worth recording: it removes the request
     * from ajaxSend/ajaxComplete entirely — and product-picker.js hangs its cache invalidation on
     * ajaxComplete. Silencing the overlay that way would leave a stale picker after every product write,
     * which is exactly the failure that hook exists to prevent.
     *
     * So the lifecycle moves to ajaxSend/ajaxComplete with a counter of BLOCKING requests only. A caller
     * passes `nonBlocking: true` in its settings; jQuery carries unknown settings through untouched, the
     * global events still fire, and only the overlay ignores it.
     */
    var blocking = 0;

    $(document).ajaxSend(function (evt, jqXHR, settings) {
      if (settings && settings.nonBlocking === true) return;
      blocking++;
      if (blocking === 1) {
        if (showTimer) clearTimeout(showTimer);
        showTimer = setTimeout(show, SHOW_DELAY_MS);
      }
    });

    $(document).ajaxComplete(function (evt, jqXHR, settings) {
      if (settings && settings.nonBlocking === true) return;
      // Never below zero: a handler added after a request began would otherwise strand the counter
      // positive and leave the overlay up for the rest of the session.
      blocking = Math.max(0, blocking - 1);
      if (blocking === 0) hide();
    });

    /*
     * A last-resort release. If a request is aborted in a way that skips ajaxComplete — a navigation, a
     * torn-down iframe — the counter could strand and the overlay would never lift. A covered screen that
     * cannot be dismissed is the worst failure this file can produce, so it is bounded.
     */
    window.setInterval(function () {
      if (blocking > 0 && $.active === 0) { blocking = 0; hide(); }
    }, 3000);
    $(document).ajaxError(hide); // belt-and-suspenders: never strand the overlay on an error
  });
})();

/*
 * ── Background reads: fetch WITHOUT holding the blocking overlay ──────────────────────────────────
 *
 * THE DEFECT THIS EXISTS FOR
 * jQuery fires `ajaxStart` on the FIRST request and `ajaxStop` only when the LAST one finishes, so the
 * overlay stays up for the slowest call in flight. On a dashboard load that is nine parallel requests, and
 * measurement put the slowest at ~970ms — meaning a cashier could not type for about a second while the
 * DASHBOARD CHARTS loaded. A customer is standing at the counter for that second.
 *
 * A blocking spinner belongs on an action the USER started — saving a sale, running a report — where the
 * answer is the point and there is nothing sensible to do until it arrives. It does not belong on the
 * background population of a screen: the right behaviour there is to show the screen and fill it in.
 *
 * WHAT `global: false` DOES
 * It excludes the request from jQuery's ajaxStart/ajaxStop lifecycle entirely, so it neither raises the
 * overlay nor holds one raised by something else. The request is otherwise completely normal.
 *
 * WHEN TO USE IT
 *   YES — populating a picker, tiles, charts, feature flags, reference lists on page load.
 *   NO  — anything the user just clicked and is waiting on, and NEVER a write. If an operator pressed a
 *         button, blocking is honest: it stops them pressing it twice.
 */
(function (global) {
    'use strict';
    var $ = global.jQuery;
    if (!$) return;

    /**
     * A GET that does not block the UI. Same signature as $.getJSON(url, success).
     *
     * Returns the jqXHR, so callers that chain .then()/.fail() keep working unchanged.
     */
    global.bgJson = function (url, success) {
        return $.ajax({ url: url, dataType: 'json', global: false, success: success });
    };

    /** As above for callers that do not want JSON parsing forced (mirrors $.get). */
    global.bgGet = function (url, success) {
        return $.ajax({ url: url, global: false, success: success });
    };
})(window);
