/*
 * Global AJAX waiting overlay — the single source for the whole application.
 *
 * Include THIS one file (no per-page markup or CSS). It self-injects two indicators and ties them to jQuery's
 * global AJAX lifecycle:
 *
 *   • the BLOCKING overlay — a full-viewport veil with a spinner — for WRITES;
 *   • a thin PROGRESS BAR along the top edge — which never covers anything — for READS.
 *
 * ⭐ BLK-1 — A READ NEVER FREEZES THE SCREEN.
 *
 * The ruling this implements: "Block the risky action, not the whole user interface." Until BLK-1 every request
 * raised the veil, and ~200 of those were reads — filling a grid, a dropdown, a dashboard chart. A cashier could
 * not type for about a second while the DASHBOARD CHARTS loaded. The decision is made here from the request's
 * method, so no call site was edited and every new one is right by default:
 *
 *   READ  (GET / HEAD)              → no veil; the progress bar after SHOW_DELAY_MS
 *   WRITE (POST / PUT / PATCH / …)  → the veil, as before
 *   `nonBlocking: true`             → no veil (PERF-13: callAjax, jsonPost); the progress bar
 *   `blocking: true`                → the veil — opt-in, for a read that genuinely must hold the screen
 *   `global: false` (bgJson/bgGet)  → nothing at all; see the note at the bottom of this file
 *
 * ⚠ WHY WRITES STILL RAISE THE VEIL — do not "finish the job" here without reading this.
 * Several money and stock writes have no server-enforced key yet. submit-once.js stops an IDENTICAL double
 * submit, but an operator who changes the amount and saves again while the first request is in flight sends a
 * different body. For the ones posted with $.post / $.ajax — sale return, purchase return, stock adjustment,
 * opening balance — the veil is what prevents that. (Forms saved through main.js's callAjax — purchases, school
 * fees — are nonBlocking and never had the veil; see the design doc for which of those have no lock at all.)
 * Writes lose the veil form by form in BLK-2, once each has a server-enforced key (BLK-13, BLK-5).
 * Design: blocking-ui-and-backend-guards-design.md §4.3.1.
 *
 * A short show-delay keeps quick calls from flashing either indicator.
 */
(function () {
  if (window.__appAjaxOverlayInstalled) return;
  window.__appAjaxOverlayInstalled = true;

  var SHOW_DELAY_MS = 220;
  var showTimer = null;
  var progressTimer = null;

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
      '@keyframes aoSpin{to{transform:rotate(360deg)}}' +
      // BLK-1: the read indicator. pointer-events:none is the whole point — it must never be the element a
      // click lands on. display (not opacity) toggles it, so "is it showing" has one unambiguous answer.
      '#appAjaxProgress{position:fixed;top:0;left:0;right:0;height:3px;z-index:100000;display:none;' +
      'pointer-events:none;overflow:hidden;background:rgba(21,101,192,.18)}' +
      '#appAjaxProgress.show{display:block}' +
      '#appAjaxProgress .ap-bar{position:absolute;top:0;bottom:0;left:-40%;width:40%;background:#1565C0;' +
      'animation:apSlide 1.1s ease-in-out infinite}' +
      '@keyframes apSlide{to{left:100%}}' +
      '@media (prefers-reduced-motion: reduce){#appAjaxProgress .ap-bar{animation:none;left:0;width:100%;opacity:.55}}';
    var s = document.createElement('style');
    s.id = 'app-ajax-overlay-style';
    s.appendChild(document.createTextNode(css));
    document.head.appendChild(s);
  }

  function injectDom() {
    if (!document.getElementById('appAjaxOverlay')) {
      var d = document.createElement('div');
      d.id = 'appAjaxOverlay';
      d.setAttribute('aria-hidden', 'true');
      d.setAttribute('role', 'status');
      d.innerHTML = '<div class="ao-box"><div class="ao-spin"></div><div class="ao-msg">Please wait…</div></div>';
      document.body.appendChild(d);
    }
    if (!document.getElementById('appAjaxProgress')) {
      var p = document.createElement('div');
      p.id = 'appAjaxProgress';
      p.setAttribute('role', 'progressbar');
      p.setAttribute('aria-label', 'Loading');
      p.setAttribute('aria-hidden', 'true');
      p.innerHTML = '<div class="ap-bar"></div>';
      document.body.appendChild(p);
    }
  }

  function toggle(id, on) {
    var el = document.getElementById(id);
    if (!el) return;
    el.classList.toggle('show', on);
    el.setAttribute('aria-hidden', on ? 'false' : 'true');
  }

  function hide() {
    if (showTimer) { clearTimeout(showTimer); showTimer = null; }
    toggle('appAjaxOverlay', false);
  }

  function hideProgress() {
    if (progressTimer) { clearTimeout(progressTimer); progressTimer = null; }
    toggle('appAjaxProgress', false);
  }

  function ready(fn) {
    if (document.readyState !== 'loading') fn();
    else document.addEventListener('DOMContentLoaded', fn);
  }

  /** A write changes something on the server. jQuery upper-cases `type` before ajaxSend; this does not rely on it. */
  function isWrite(settings) {
    var m = String((settings && (settings.type || settings.method)) || 'GET').toUpperCase();
    return m !== 'GET' && m !== 'HEAD';
  }

  /**
   * Does this request hold the whole screen? ajaxSend and ajaxComplete receive the SAME settings object, so
   * asking this one function at both ends is what keeps the two counters balanced.
   */
  function blocks(settings) {
    if (!settings) return false;
    if (settings.blocking === true) return true;       // explicit opt-in wins
    if (settings.nonBlocking === true) return false;   // PERF-13 writes (callAjax, jsonPost)
    return isWrite(settings);
  }

  ready(function () {
    if (!window.jQuery) return; // requires jQuery's global ajax events
    injectStyles();
    injectDom();
    var $ = window.jQuery;
    /*
     * PERF-13 — the lifecycle runs on ajaxSend/ajaxComplete with COUNTERS, not ajaxStart/ajaxStop, because
     * those two only say "something is in flight" and cannot tell a read from a write.
     *
     * ⚠ `global: false` is NOT how a request opts out of the veil: it removes the request from ajaxSend/
     * ajaxComplete entirely, and product-picker.js hangs its cache invalidation on ajaxComplete. A write that
     * went silent that way would leave a stale picker after every product save.
     */
    var blocking = 0;   // requests holding the veil
    var reading = 0;    // requests showing the progress bar

    $(document).ajaxSend(function (evt, jqXHR, settings) {
      if (blocks(settings)) {
        blocking++;
        if (blocking === 1) {
          if (showTimer) clearTimeout(showTimer);
          showTimer = setTimeout(function () { toggle('appAjaxOverlay', true); }, SHOW_DELAY_MS);
        }
      } else {
        reading++;
        if (reading === 1) {
          if (progressTimer) clearTimeout(progressTimer);
          progressTimer = setTimeout(function () { toggle('appAjaxProgress', true); }, SHOW_DELAY_MS);
        }
      }
    });

    $(document).ajaxComplete(function (evt, jqXHR, settings) {
      // Never below zero: a handler added after a request began would otherwise strand a counter positive
      // and leave an indicator up for the rest of the session.
      if (blocks(settings)) {
        blocking = Math.max(0, blocking - 1);
        if (blocking === 0) hide();
      } else {
        reading = Math.max(0, reading - 1);
        if (reading === 0) hideProgress();
      }
    });

    /*
     * A last-resort release. If a request is aborted in a way that skips ajaxComplete — a navigation, a
     * torn-down iframe — a counter could strand and an indicator would never lift. A covered screen that
     * cannot be dismissed is the worst failure this file can produce, so it is bounded.
     */
    window.setInterval(function () {
      if ($.active === 0) {
        if (blocking > 0) { blocking = 0; hide(); }
        if (reading > 0) { reading = 0; hideProgress(); }
      }
    }, 3000);
    $(document).ajaxError(hide); // belt-and-suspenders: never strand the overlay on an error
  });
})();

/*
 * ── Background reads: fetch WITHOUT any indicator ─────────────────────────────────────────────────
 *
 * Since BLK-1 an ordinary read no longer raises the veil — it shows the thin progress bar instead. These
 * helpers go one step further and show NOTHING, which is right for work the user did not ask for: populating
 * a picker, tiles, charts, feature flags, reference lists on page load. A bar that flickers every time a
 * picker refreshes in the background teaches people to ignore it.
 *
 * WHAT `global: false` DOES
 * It excludes the request from jQuery's global events entirely, so neither indicator sees it. The request is
 * otherwise completely normal.
 *
 * WHEN TO USE IT
 *   YES — background population of a screen the user is already looking at.
 *   NO  — anything the user just clicked and is waiting on, and NEVER a write: a write excluded from the
 *         global events also escapes submit-once.js's in-flight lock and product-picker.js's invalidation.
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
