/*
 * PR-1 — what a purchase does to the selling price, said BEFORE it is saved, and what has happened to it since.
 *
 * 1. The purchase form's hint (#purchasePriceEffect), under the rates. The tenant's setting
 *    pos.pricing.purchaseMode decides whether a purchase's sell rate becomes the product's price for EVERY unit in
 *    stock (latest, today's behaviour) or never touches it (keep). Re-pricing old stock is the surprise this
 *    feature exists to remove, so the form says which one is about to happen, with both numbers.
 *    Rendered from calculateNetPurchase(), which every rate keystroke and every prefill already runs.
 *
 * 2. The product form's "Price history" dialog (owner/admin; catalog refuses anyone else, because the answer
 *    carries the cost). Every selling-price change is recorded by catalog in the change's own transaction:
 *    product edit, purchase (naming the bill), CSV import.
 *
 * Reads window.posPurchasePriceMode, set by loadPosFeatureFlags() from /getBusinessConfig. Absent (config not
 * loaded, or failed) means LATEST — the default, and what the server does when it cannot read the setting.
 */
(function (global) {
    'use strict';

    function money(v) {
        var n = Number(v);
        return (v === null || v === undefined || v === '' || isNaN(n)) ? '' : n.toFixed(2);
    }

    function currentPurchasePrice() {
        // The picker option carries the catalog price (data-price) — the same value the form prefilled from.
        var v = $('#purchaseItemDD :selected').attr('data-price');
        return (v === undefined || v === null || v === '') ? null : Number(v);
    }

    function render() {
        var $hint = $('#purchasePriceEffect');
        if (!$hint.length) return;
        var productId = $('#purchaseItemDD').val();
        var typed = $('#purchaseSellRate').val();
        var sell = Number(typed);
        if (!productId || typed === '' || !(sell > 0)) { $hint.hide().empty().removeAttr('data-effect'); return; }

        var now = currentPurchasePrice();
        var keep = global.posPurchasePriceMode === 'keep';
        var effect, text;
        if (keep) {
            effect = 'keep';
            text = (now != null)
                ? t('ui.js.priceEffectKeep', money(now))
                : t('ui.js.priceEffectKeepNone');
        } else if (now == null || !(now > 0)) {
            effect = 'set';
            text = t('ui.js.priceEffectSet', money(sell));
        } else if (Math.abs(now - sell) < 0.005) {
            effect = 'same';
            text = t('ui.js.priceEffectSame', money(now));
        } else {
            effect = 'change';
            text = t('ui.js.priceEffectChange', money(now), money(sell));
        }
        // data-effect: what the screen is SAYING, as a word a test (or a reader of the DOM) can check without
        // parsing a sentence in six languages.
        $hint.attr('data-effect', effect).text(text).show();
    }

    // ── 2. The product's price history ─────────────────────────────────────────────────────────

    var SOURCE_KEYS = {
        MANUAL: 'ui.js.priceSourceManual',
        PURCHASE: 'ui.js.priceSourcePurchase',
        IMPORT: 'ui.js.priceSourceImport'
    };
    function sourceLabel(s) { return SOURCE_KEYS[s] ? t(SOURCE_KEYS[s]) : (s || ''); }

    /** The server sends an instant with its offset; show it in THIS browser's time, to the minute. */
    function when(v) {
        if (!v) return '';
        var d = new Date(v);
        if (isNaN(d.getTime())) return String(v);
        var p = function (n) { return (n < 10 ? '0' : '') + n; };
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
    }

    function open(productId) {
        productId = productId || $('#productId').val();
        if (!productId) return;
        var d = buildFinanceDialog('PriceHistoryDialog');
        d.style.display = 'flex';
        var title = document.getElementById('PriceHistoryDialogTitle');
        var body = document.getElementById('PriceHistoryDialogBody');
        title.textContent = t('ui.js.priceHistory');
        body.innerHTML = '<div style="padding:8px">' + escHtml(t('ui.js.loading')) + '</div>';
        $.get(serverContext + 'productPriceHistory?productId=' + encodeURIComponent(productId), function (resp) {
            var data = resp && resp.success !== false ? resp.data : null;
            if (!data) {
                body.innerHTML = '<div class="alert alert-warning" id="priceHistoryRefused">'
                    + escHtml((resp && resp.message) || t('ui.js.priceHistoryUnavailable')) + '</div>';
                return;
            }
            title.textContent = t('ui.js.priceHistory') + ' — ' + (data.name || ('#' + productId));
            var h = '<div id="priceHistoryNow" style="margin-bottom:10px">'
                + '<b>' + escHtml(t('ui.js.priceHistoryCurrent')) + '</b> <span data-k="sellingPrice">' + escHtml(money(data.sellingPrice)) + '</span>'
                + ' &nbsp;·&nbsp; <b>' + escHtml(t('ui.js.priceHistoryLastCost')) + '</b> <span data-k="lastPurchaseRate">' + escHtml(money(data.lastPurchaseRate)) + '</span>'
                + '</div>';
            var rows = data.history || [];
            if (!rows.length) {
                h += '<div style="padding:8px;color:#777" id="priceHistoryEmpty">' + escHtml(t('ui.js.priceHistoryEmpty')) + '</div>';
            } else {
                h += '<div class="table-responsive"><table class="table table-striped" id="priceHistoryTable" style="width:100%"><thead><tr>'
                    + '<th>' + escHtml(t('ui.js.priceHistoryDate')) + '</th>'
                    + '<th class="text-right">' + escHtml(t('ui.js.priceHistoryFrom')) + '</th>'
                    + '<th class="text-right">' + escHtml(t('ui.js.priceHistoryTo')) + '</th>'
                    + '<th>' + escHtml(t('ui.js.priceHistorySource')) + '</th>'
                    + '<th>' + escHtml(t('ui.js.priceHistoryRef')) + '</th>'
                    + '</tr></thead><tbody>';
                rows.forEach(function (r) {
                    h += '<tr data-source="' + escHtml(r.source || '') + '">'
                        + '<td>' + escHtml(when(r.changedAt)) + '</td>'
                        + '<td class="text-right" data-k="old">' + escHtml(money(r.oldPrice)) + '</td>'
                        + '<td class="text-right" data-k="new">' + escHtml(money(r.newPrice)) + '</td>'
                        + '<td>' + escHtml(sourceLabel(r.source)) + '</td>'
                        + '<td data-k="ref">' + escHtml(r.ref || '') + '</td></tr>';
                });
                h += '</tbody></table></div>';
            }
            body.innerHTML = h;
        }).fail(function () {
            body.innerHTML = '<div class="alert alert-warning">' + escHtml(t('ui.js.priceHistoryUnavailable')) + '</div>';
        });
    }

    /** The button lives on the product form; it means something only for a product that exists. */
    function syncButton() {
        $('#prodPriceHistoryBtn').toggle(!!$('#productId').val());
    }

    global.PriceHistory = { render: render, open: open, syncButton: syncButton };
    global.renderPurchasePriceEffect = render;
})(window);
