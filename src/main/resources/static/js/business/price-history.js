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

    // ── PR-2: the markup rule's suggestion (asked of the server — the form never does the arithmetic) ──────────

    var sug = { key: null, data: null, timer: null };

    function pctText(v) { var n = Number(v); return isNaN(n) ? '' : String(Math.round(n * 100) / 100); }

    /** "14.5% on cost, the business rate" — why this number, so the owner can check the rule did what they meant. */
    function why(d) {
        var basis = d.basis === 'margin' ? t('ui.js.markupMargin', pctText(d.pct)) : t('ui.js.markupOnCost', pctText(d.pct));
        var src = d.pctSource === 'PRODUCT' ? t('ui.js.markupOwn')
            : d.pctSource === 'CATEGORY' ? t('ui.js.markupCat') : t('ui.js.markupBiz');   // PR-2b
        return basis + ', ' + src;
    }

    /** Ask the server what the rule says for (product, cost) — once per change, debounced, late answers dropped. */
    function requestSuggestion() {
        var productId = $('#purchaseItemDD').val();
        var cost = Number($('#purchasePurchaseRate').val());
        if (!productId || !(cost > 0)) { sug.key = null; sug.data = null; clearTimeout(sug.timer); return; }
        var key = productId + '|' + cost;
        if (key === sug.key) return;
        sug.key = key; sug.data = null;
        clearTimeout(sug.timer);
        sug.timer = setTimeout(function () {
            // bgJson: background work never holds the blocking overlay over a purchase being typed.
            bgJson(serverContext + 'suggestedPrice?productId=' + encodeURIComponent(productId) + '&cost=' + encodeURIComponent(cost),
                function (resp) {
                    if (sug.key !== key) return;   // the operator has moved to another product or cost
                    sug.data = (resp && resp.status === 'SUCCESS') ? (resp.object || resp.data || null) : null;
                    render();
                });
        }, 250);
    }

    /** The current answer, only if it is for what is on screen now. */
    function suggestion() {
        var key = $('#purchaseItemDD').val() + '|' + Number($('#purchasePurchaseRate').val());
        return (sug.data && sug.key === key) ? sug.data : null;
    }

    function renderSuggest(d) {
        var $s = $('#purchaseSuggest');
        if (!$s.length) return;
        // Auto says it on the price line itself (below); Off and "no rule" say nothing.
        if (!d || d.price == null || d.mode === 'off' || d.mode === 'auto') {
            $s.hide().empty().removeAttr('data-mode').removeAttr('data-price').removeAttr('data-sig'); return;
        }
        var price = money(d.price);
        var mode = d.purchaseMode === 'keep' ? 'keep' : d.mode;
        var offer = mode !== 'keep' && money($('#purchaseSellRate').val()) !== price;
        /*
         * Rebuild ONLY when what it says changes. Clicking "Use" while the cursor is still in P/U blurs P/U first,
         * and its onblur re-renders this line — a rebuild there replaced the button between mousedown and mouseup,
         * so the click was lost and S/U never changed (found by gate M9).
         */
        var sig = [mode, price, why(d), offer].join('|');
        if ($s.attr('data-sig') === sig && $s.is(':visible')) return;
        $s.attr('data-sig', sig).attr('data-mode', mode).attr('data-price', price).empty();
        if (mode === 'keep') {
            $s.append($('<span>').text(t('ui.js.markupKeep', price, why(d))));
        } else {
            $s.append($('<span>').text(t('ui.js.markupSuggest', price, why(d)) + ' '));
            if (offer) {
                $('<button type="button" class="btn btn-default btn-xs" id="purchaseSuggestApply">')
                    .text(t('ui.js.markupUse', price)).appendTo($s);
            }
        }
        $s.show();
    }

    // One delegated handler: survives any re-render, and reads the price the line is SHOWING.
    $(document).on('click', '#purchaseSuggestApply', function () {
        var price = $('#purchaseSuggest').attr('data-price');
        if (!price) return;
        $('#purchaseSellRate').val(price);
        if (typeof calculateNetPurchase === 'function') calculateNetPurchase(); else render();
    });

    function render() {
        var $hint = $('#purchasePriceEffect');
        if (!$hint.length) return;
        requestSuggestion();
        var d = suggestion();
        renderSuggest(d);
        var productId = $('#purchaseItemDD').val();
        var typed = $('#purchaseSellRate').val();
        var sell = Number(typed);
        var keep = global.posPurchasePriceMode === 'keep';
        var perBatch = global.posPurchasePriceMode === 'per_batch';
        var effect, text;

        // PR-3b Per batch: the price belongs to THIS purchase's stock; the product's price and older stock are untouched.
        // With the markup rule on Auto the batch gets the rule's price (no guards: a new batch has no price to protect).
        if (productId && perBatch) {
            var batchPrice = (d && d.mode === 'auto' && d.price != null) ? Number(d.price) : (sell > 0 ? sell : null);
            var nowP = currentPurchasePrice();
            if (batchPrice == null) { $hint.hide().empty().removeAttr('data-effect'); return; }
            $hint.attr('data-effect', 'batch')
                .text(t('ui.js.priceEffectBatch', money(batchPrice), nowP != null ? money(nowP) : '—')).show();
            return;
        }

        // PR-2 Auto: the RULE decides the price, whatever S/U says — so the line speaks for the rule.
        if (productId && !keep && d && d.mode === 'auto' && d.price != null) {
            var cur = d.current != null ? Number(d.current) : currentPurchasePrice();
            if (d.autoApplies) {
                if (cur != null && Math.abs(cur - Number(d.price)) < 0.005) {
                    effect = 'auto-same'; text = t('ui.js.priceEffectAutoSame', money(cur));
                } else {
                    effect = 'auto'; text = t('ui.js.priceEffectAuto', money(cur), money(d.price), why(d));
                }
            } else if (d.guard === 'NEVER_LOWER') {
                effect = 'held'; text = t('ui.js.priceEffectHeldLower', money(cur), money(d.price));
            } else {
                effect = 'held'; text = t('ui.js.priceEffectHeldRise', money(cur), money(d.price));
            }
            $hint.attr('data-effect', effect).text(text).show();
            return;
        }

        if (!productId || typed === '' || !(sell > 0)) { $hint.hide().empty().removeAttr('data-effect'); return; }

        var now = currentPurchasePrice();
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
        IMPORT: 'ui.js.priceSourceImport',
        MARKUP: 'ui.js.priceSourceMarkup'
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

    // ── 3. PR-2b: Markup by category (Settings → Markup by category) ─────────────────────────────

    function showCategoryMarkup() {
        $('.formDiv').hide();
        $('#CategoryMarkupDiv').show();
        var $body = $('#tableCategoryMarkup tbody').empty()
            .append($('<tr>').append($('<td colspan="3">').text(t('ui.js.loading'))));
        $('#cmBiz').remove();
        // Read the business's % HERE, not from the page-load flags: opened quickly, those may not have arrived yet,
        // and the line then said "no markup set" for a business that has one (found by gate M13).
        bgJson(serverContext + 'getBusinessConfig', function (res) {
            var biz = Number(posSettingText(res, 'pos.pricing.markupPct', '0'));
            global.posMarkupPct = String(biz);
            $('#cmBiz').remove();
            $('#cmHelp').after($('<p class="help-block" id="cmBiz">').text(biz > 0
                ? t('ui.js.cmBusinessPct', String(Math.round(biz * 100) / 100)) : t('ui.js.cmBusinessNone')));
        });
        bgJson(serverContext + 'getUserCategories', function (resp) {
            var rows = (resp && resp.categories) || [];
            $body.empty();
            if (!rows.length) {
                $body.append($('<tr>').append($('<td colspan="3" class="text-muted">').text(t('ui.js.cmNoCategories'))));
                return;
            }
            rows.forEach(function (c) {
                var $in = $('<input type="number" step="any" min="0" max="1000" class="form-control input-sm">')
                    .attr('id', 'cmPct_' + c.id).attr('data-category', c.id)
                    .attr('placeholder', t('ui.js.cmBlank'))
                    .val(c.markupPct != null ? Number(c.markupPct) : '')
                    .attr('data-saved', c.markupPct != null ? String(Number(c.markupPct)) : '');
                $body.append($('<tr>').attr('data-category', c.id)
                    .append($('<td>').text(c.name || ('#' + c.id)))
                    .append($('<td>').append($in))
                    .append($('<td class="cm-state" aria-live="polite">')));
            });
        });
    }

    /** Save a row when its box is left — only if it changed. Blank = clear (the business's % applies). */
    $(document).on('change', '#tableCategoryMarkup input[data-category]', function () {
        var $in = $(this), $state = $in.closest('tr').find('.cm-state');
        var raw = String($in.val() || '').trim();
        if (raw === ($in.attr('data-saved') || '')) return;
        $state.removeClass('text-danger text-success').text(t('ui.js.cmSaving'));
        // global:false keeps the blocking overlay off a settings row — and therefore skips the ajaxSend hook that adds
        // the CSRF header (header.html, L16), so it is passed here. Without it every save was a 403 (found by gate M13).
        $.ajax({ type: 'POST', url: serverContext + 'setCategoryMarkup', contentType: 'application/json', global: false,
            headers: (typeof xsrfHeaders === 'function') ? xsrfHeaders() : {},
            data: JSON.stringify({ categoryId: Number($in.attr('data-category')), markupPct: raw === '' ? null : Number(raw) }) })
            .done(function (resp) {
                if (resp && resp.success !== false) {
                    var saved = resp.data && resp.data.markupPct != null ? String(Number(resp.data.markupPct)) : '';
                    $in.attr('data-saved', saved).val(saved);
                    $state.addClass('text-success').text(t('ui.js.cmSaved'));
                } else {
                    $state.addClass('text-danger').text((resp && resp.message) || t('ui.js.cmFailed'));
                }
            })
            .fail(function () { $state.addClass('text-danger').text(t('ui.js.cmFailed')); });
    });

    global.showCategoryMarkup = showCategoryMarkup;
    global.PriceHistory = { render: render, open: open, syncButton: syncButton };
    global.renderPurchasePriceEffect = render;
})(window);
