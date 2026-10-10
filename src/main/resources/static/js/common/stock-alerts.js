/*
 * stock-alerts.js — STK-ALERT: the header badge an owner or admin acts on.
 *
 * Three counts from /stockAlertSummary (business-service decides them; this file only shows them):
 *   expired   batches past expiry that still hold stock            (red)
 *   expiring  batches expiring within the business's window        (amber)
 *   low       products at or below their own minimum, else the business cap (Configuration → Stock alerts) (red)
 *
 * ── WHY IT PULSES AND THEN STOPS ────────────────────────────────────────────────────────────────────────
 * Decided with the owner (2026-10-10): a badge that blinks all day is tuned out within days and is a distraction at
 * a counter; WCAG 2.2.2 also asks that motion lasting more than 5 seconds can be stopped. So it pulses three times
 * when it first appears on a page, and again whenever a count RISES, then stays solid red. "Reduce motion" removes
 * the pulse entirely (the CSS below).
 *
 * The <li> exists only for owner/admin/super (header.html, sec:authorize) and only the business dashboard loads this
 * file, so every other page and role shows nothing. Hidden while every count is zero.
 */
(function (global, $) {
    'use strict';

    var $nav = $('#stockAlertNav');
    if (!$nav.length) return;

    var last = null;        // the previous answer's counts — a RISE pulses again
    var refreshTimer = null;

    function tr(key, fallback) {
        var args = Array.prototype.slice.call(arguments, 2);
        if (typeof global.t === 'function' && typeof global.tHas === 'function' && global.tHas(key)) {
            return global.t.apply(null, [key].concat(args));
        }
        return String(fallback).replace(/\{(\d+)\}/g, function (m, i) { return args[i] == null ? m : String(args[i]); });
    }
    function esc(v) {
        return (typeof global.escHtml === 'function') ? global.escHtml(v == null ? '' : String(v)) : String(v == null ? '' : v);
    }
    function num(v) { var n = Number(v); return isFinite(n) ? n : 0; }
    function qty(v) { var n = Number(v); return isFinite(n) ? String(Math.round(n * 100) / 100) : ''; }

    function injectCss() {
        if (document.getElementById('saCss')) return;
        var css = ''
            + '.sa-btn{display:inline-flex;align-items:center;gap:5px;background:rgba(255,255,255,.12);border:0;color:#fff;'
            + 'border-radius:100px;padding:4px 10px;font-size:11.5px;cursor:pointer}'
            + '.sa-btn:focus-visible{outline:2px solid #fff;outline-offset:2px}'
            + '.sa-badge{display:inline-block;border-radius:100px;padding:1px 8px;font-weight:700;font-size:11px;color:#fff;white-space:nowrap}'
            + '.sa-red{background:#dc2626}.sa-amber{background:#d97706}'
            + '@keyframes saPulse{0%{box-shadow:0 0 0 0 rgba(220,38,38,.75)}70%{box-shadow:0 0 0 9px rgba(220,38,38,0)}100%{box-shadow:0 0 0 0 rgba(220,38,38,0)}}'
            + '.sa-pulse{animation:saPulse 1.1s ease-out 3}'
            + '@media (prefers-reduced-motion: reduce){.sa-pulse{animation:none}}'
            + '.sa-panel{position:absolute;right:0;top:100%;z-index:1050;width:min(380px,92vw);max-height:70vh;overflow:auto;'
            + 'background:#fff;color:#1e293b;border-radius:10px;box-shadow:0 12px 32px rgba(0,0,0,.25);padding:10px 12px;font-size:13px}'
            + '.sa-panel h5{margin:8px 0 4px;font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.03em;color:#475569}'
            + '.sa-panel ul{list-style:none;margin:0;padding:0}.sa-panel li{display:flex;justify-content:space-between;gap:10px;'
            + 'padding:4px 0;border-bottom:1px solid #f1f5f9}.sa-panel li span:last-child{color:#64748b;white-space:nowrap}'
            + '.sa-panel .sa-more{color:#64748b;font-size:12px;padding-top:4px}';
        var st = document.createElement('style');
        st.id = 'saCss';
        st.appendChild(document.createTextNode(css));
        document.head.appendChild(st);
    }

    var data = null;

    function render(d) {
        if (!d || d.success === false) return;          // a failed read keeps what is on screen
        data = d;
        var c = { expired: num(d.expired), expiring: num(d.expiring), low: num(d.low) };
        var labels = {
            expired: tr('ui.js.saExpired', 'Expired {0}', c.expired),
            expiring: tr('ui.js.saExpiring', 'Expiring {0}', c.expiring),
            low: tr('ui.js.saLow', 'Low stock {0}', c.low)
        };
        var total = c.expired + c.expiring + c.low;
        var rose = false;
        Object.keys(c).forEach(function (k) {
            var $b = $nav.find('[data-sa="' + k + '"]');
            $b.text(labels[k]).prop('hidden', c[k] <= 0);
            if (c[k] > 0 && (last === null || c[k] > last[k])) rose = true;
        });
        var $btn = $('#stockAlertBtn');
        $btn.attr('aria-label', tr('ui.js.saTitle', 'Stock alerts') + ': '
            + [labels.expired, labels.expiring, labels.low].filter(function (x, i) { return [c.expired, c.expiring, c.low][i] > 0; }).join(', '));
        if (total <= 0) {
            $nav.css('display', 'none');
            closePanel();
        } else {
            $nav.css('display', 'flex');
            if (rose) {
                $btn.removeClass('sa-pulse');
                void $btn[0].offsetWidth;                // restart the animation
                $btn.addClass('sa-pulse');
            }
        }
        last = c;
        if (!$('#stockAlertPanel').prop('hidden')) fillPanel();
    }

    function section(title, items, more, line) {
        if (!items || !items.length) return '';
        var h = '<h5>' + esc(title) + '</h5><ul>';
        items.forEach(function (it) { h += '<li><span>' + esc(it.name || ('#' + it.productId)) + '</span><span>' + esc(line(it)) + '</span></li>'; });
        h += '</ul>';
        if (more > items.length) h += '<div class="sa-more">' + esc(tr('ui.js.saMore', '…and {0} more', more - items.length)) + '</div>';
        return h;
    }

    function fillPanel() {
        var d = data || {};
        var h = section(tr('ui.js.saExpiredHd', 'Expired — still in stock'), d.expiredItems, num(d.expired),
                function (it) { return tr('ui.js.saBatchLine', '{0} · expired {1}', qty(it.quantity), it.expiryDate || ''); })
            + section(tr('ui.js.saExpiringHd', 'Expiring within {0} days', d.nearDays), d.expiringItems, num(d.expiring),
                function (it) { return tr('ui.js.saExpiresLine', '{0} · expires {1}', qty(it.quantity), it.expiryDate || ''); })
            + section(tr('ui.js.saLowHd', 'Low stock'), d.lowItems, num(d.low),
                function (it) { return tr('ui.js.saLowLine', '{0} left (min {1})', qty(it.onHand), qty(it.min)); });
        $('#stockAlertPanel').html(h || '<div>' + esc(tr('ui.js.saNone', 'Nothing needs attention.')) + '</div>');
    }

    function openPanel() { fillPanel(); $('#stockAlertPanel').prop('hidden', false); $('#stockAlertBtn').attr('aria-expanded', 'true'); }
    function closePanel() { $('#stockAlertPanel').prop('hidden', true); $('#stockAlertBtn').attr('aria-expanded', 'false'); }

    function load() {
        // global:false — never the blocking overlay, and never in ajaxComplete (so it cannot trigger itself).
        $.ajax({ url: (global.serverContext || '/') + 'stockAlertSummary', dataType: 'json', global: false })
            .done(render);
    }

    /** After anything that moves stock or the settings, read again — once, shortly after the burst ends. */
    var MOVES = /\/(addSell|updateSell|voidSell|saleReturn|addPurchase|updatePurchase|voidPurchase|purchaseReturn|addProductStock|adjustStock|stockAdjust[A-Za-z]*|saveBusinessConfig|resetBusinessConfig|dispensePrescription)(\?|$)/;
    $(document).ajaxComplete(function (e, xhr, settings) {
        if (!settings || !MOVES.test(String(settings.url || ''))) return;
        clearTimeout(refreshTimer);
        refreshTimer = setTimeout(load, 1500);
    });

    $(document).on('click', '#stockAlertBtn', function (e) {
        e.preventDefault();
        if ($('#stockAlertPanel').prop('hidden')) openPanel(); else closePanel();
    });
    $(document).on('click', function (e) { if (!$(e.target).closest('#stockAlertNav').length) closePanel(); });
    $(document).on('keydown', function (e) { if (e.key === 'Escape') closePanel(); });

    injectCss();
    load();
    setInterval(function () { if (document.visibilityState === 'visible') load(); }, 5 * 60 * 1000);

    global.StockAlerts = { refresh: load };
})(window, jQuery);
