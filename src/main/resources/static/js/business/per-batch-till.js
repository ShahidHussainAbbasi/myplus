/**
 * PR-3c — the till in a Per-batch shop: each batch sells at its own price, and the cart shows it before payment.
 *
 * Design: microservices/docs/selling-price-per-purchase-analysis.md §10.3 (PR-3c). Inert unless
 * `window.posPurchasePriceMode === 'per_batch'` (Settings › Purchasing › How a purchase affects the selling price),
 * and never while an invoice is being EDITED — an edit keeps the rates it was sold at.
 *
 * Three hooks, and the server stays the judge of every one:
 *   1. onBatches  — picking an item fills a "Batch" choice (the shop's pick rule first) and puts the chosen batch's
 *                   price in the rate box. The box value is the till's own, so the server may re-price it from the
 *                   batch; a price the cashier TYPES is theirs and always wins.
 *   2. beforeAdd  — Add to Cart asks the server how that line will be charged (/batchPricePreview) and puts the
 *                   answer in the cart: "abc-123  7 @ 200.00 (B-0912)" and "3 @ 250.00 (B-1003)" as two lines.
 *   3. beforeComplete — Complete Sale asks again for the WHOLE cart (two lines of one product share batches). If the
 *                   answer differs from the cart, the cart is replaced, the cashier is told, and nothing is posted:
 *                   what the customer is charged is what they were shown.
 * A preview that cannot be reached never blocks a sale — the server prices and checks the sale itself.
 */
(function (global) {
    'use strict';

    var $ = global.jQuery;
    if (!$) return;

    function t(key, fallback, args) {
        var s = (global.t && global.tHas && global.tHas(key)) ? global.t(key) : fallback;
        if (args) for (var i = 0; i < args.length; i++) s = s.replace('{' + i + '}', args[i]);
        return s;
    }
    function esc(v) { return global.escHtml ? global.escHtml(v) : String(v == null ? '' : v); }
    function money(v) { return (Math.round(Number(v || 0) * 100) / 100).toFixed(2); }

    function enabled() {
        return global.posPurchasePriceMode === 'per_batch' && !(global.editingInvoice && global.editingInvoice.chId);
    }

    var batches = [];          // the picked product's batches, FEFO order (from /productStock)
    var productPrice = null;   // its catalog price — what a batch without its own price sells at

    function priceOf(b) { return (b && b.sellPrice != null) ? Number(b.sellPrice) : Number(productPrice); }

    /** Hook 1 — a product was picked on the sale form. Returns the price to put in the rate box (or null). */
    function onBatches(list, catalogPrice) {
        var $row = $('#sellBatchPickRow'), $sel = $('#sellBatchPick');
        batches = [];
        productPrice = catalogPrice;
        if (!enabled() || !list || !list.length) { $row.hide(); $sel.empty(); return null; }
        batches = list.filter(function (b) { return b && b.stockEntryId != null; });
        if (!batches.length) { $row.hide(); return null; }
        var html = '<option value="">' + esc(t('ui.js.batchPickAuto', 'Earliest expiry first (FEFO)')) + '</option>';
        batches.forEach(function (b) {
            html += '<option value="' + esc(b.stockEntryId) + '" data-price="' + esc(priceOf(b)) + '">'
                + esc((b.batchNo || t('ui.js.batchNoNumber', 'no batch no.')) + ' · ' + Number(b.available)
                    + ' @ ' + money(priceOf(b)) + (b.expiryDate ? ' · exp ' + b.expiryDate : ''))
                + '</option>';
        });
        $sel.html(html).val('');
        $row.show();
        return priceOf(batches[0]);
    }

    /** The cashier chose a batch: its price goes in the box, and is the till's own (not typed). */
    function onPick() {
        var id = $('#sellBatchPick').val();
        var b = null;
        batches.forEach(function (x) { if (String(x.stockEntryId) === String(id)) b = x; });
        var price = b ? priceOf(b) : (batches.length ? priceOf(batches[0]) : null);
        if (price == null || isNaN(price)) return;
        $('#sellSellRate').val(money(price));
        global._sellAutoRate = Number(money(price));
        if (typeof global.calculateNetSell === 'function') global.calculateNetSell();
    }

    function chosenEntry() {
        var v = $('#sellBatchPick').val();
        return (enabled() && v) ? Number(v) : null;
    }

    function preview(sales, customer) {
        return $.ajax({
            url: global.serverContext + 'batchPricePreview', type: 'POST', contentType: 'application/json',
            data: JSON.stringify({ sales: sales, customer: customer || {} }), dataType: 'json',
            global: false,                                                     // no overlay on the till
            headers: (typeof global.xsrfHeaders === 'function') ? global.xsrfHeaders() : {}   // L16: global:false skips ajaxSend
        });
    }

    /** One cart line per part, copied from the line it came from. */
    function partLine(from, part) {
        var line = JSON.parse(JSON.stringify(from));
        var qty = Number(part.quantity), rate = Number(part.rate);
        line.quantity = qty;
        line.sellRate = rate;
        line.autoRate = rate;                       // the server priced it; not typed
        line.stockEntryId = part.stockEntryId != null ? part.stockEntryId : null;
        line.bonusQuantity = part.bonusQuantity != null ? part.bonusQuantity : null;
        line.batchNote = part.batch || null;        // shown in the cart beside the item
        line.stock = line.stock || {};
        line.stock.bsellRate = rate;
        if (part.discount != null && !(line.stock.bsellDiscountType === '1' || line.stock.bsellDiscountType === '%')) {
            line.stock.bsellDiscount = Number(part.discount) > 0 ? Number(part.discount) : '';
        }
        line.totalAmount = Math.round(qty * rate * 100) / 100;
        line.netAmount = line.totalAmount;
        if (part.batch && line.itemName && line.itemName.indexOf(' — ' + part.batch) < 0) {
            line.itemName = String(line.itemName).split(' — Batch ')[0] + ' — ' + part.batch;
        }
        return line;
    }

    function note(parts) {
        return parts.map(function (p) {
            return Number(p.quantity) + ' @ ' + money(p.rate) + (p.batch ? ' (' + String(p.batch).replace(/^Batch /, '') + ')' : '');
        }).join(' + ');
    }

    function showNote(msg) {
        var $n = $('#sellBatchNote');
        if (!$n.length) return;
        if (!msg) {
            $n.hide().empty();
            if (typeof global.syncSellNoticeRow === 'function') global.syncSellNoticeRow();   // collapse it if nothing else shows
            return;
        }
        $n.html('<span class="glyphicon glyphicon-tags"></span> ' + esc(msg)).show();
        // The notice row is collapsed (pos-notice-empty) unless a notice opens it; this note opens it itself rather
        // than changing syncSellNoticeRow's rule, which governs the older notices on every shop's till.
        $n.closest('.pos-fullrow').removeClass('pos-notice-empty');
    }

    /**
     * Hook 2 — Add to Cart. Calls done(lines) with the line(s) to put in the cart. Calls done(null) when the server
     * refused (out of stock): the line is not added and the reason is on screen.
     */
    function beforeAdd(obj, customer, done) {
        if (!enabled()) { done([obj]); return; }
        obj.stockEntryId = chosenEntry();
        if (!customer) {   // the customer chosen on the sale form, so a contract price shows here as it will be charged
            var cid = $('#sellCustomerDD').val();
            customer = cid ? { customerId: Number(cid) } : {};
        }
        preview([obj], customer).done(function (r) {
            if (!r || r.status === 'FAILED') {
                if (typeof global.showFormError === 'function') global.showFormError((r && r.message) || 'Not enough stock.');
                done(null);
                return;
            }
            if (r.status !== 'SUCCESS' || !r.perBatch || !r.parts || !r.parts.length) { done([obj]); return; }
            var lines = r.parts.map(function (p) { return partLine(obj, p); });
            showNote(r.parts.length > 1
                ? t('ui.js.batchSplitNote', 'Priced by batch: {0}', [note(r.parts)])
                : (r.parts[0].batch ? t('ui.js.batchPricedNote', 'Priced from {0}: {1}', [r.parts[0].batch, money(r.parts[0].rate)]) : null));
            done(lines);
        }).fail(function () { done([obj]); });   // the server prices the sale anyway
    }

    /**
     * Hook 3 — Complete Sale. Calls proceed() when the cart already says what will be charged; otherwise replaces the
     * cart with the server's lines, says so, and does not proceed (the cashier checks and presses Complete again).
     */
    function beforeComplete(customerHistory, proceed) {
        if (!enabled() || !customerHistory || !customerHistory.sales || !customerHistory.sales.length) { proceed(); return; }
        var cart = customerHistory.sales;
        preview(cart, customerHistory.customer).done(function (r) {
            if (!r || r.status === 'FAILED') {
                if (typeof global.showFormError === 'function') global.showFormError((r && r.message) || 'Not enough stock.');
                return;
            }
            if (r.status !== 'SUCCESS' || !r.perBatch || !r.parts) { proceed(); return; }
            // Walk the parts back onto the cart lines: each line's parts are consecutive and add up to its quantity.
            var out = [], changed = false, k = 0;
            for (var i = 0; i < cart.length; i++) {
                var line = cart[i], want = Number(line.quantity) || 0, got = 0, mine = [];
                while (k < r.parts.length && String(r.parts[k].productId) === String(line.productId)
                        && got < want - 1e-9) {
                    mine.push(r.parts[k]); got += Number(r.parts[k].quantity) || 0; k++;
                }
                if (!mine.length) { out.push(line); continue; }
                if (mine.length > 1 || Number(mine[0].rate) !== Number(line.sellRate)) changed = true;
                mine.forEach(function (p) { out.push(mine.length === 1 && !changed ? line : partLine(line, p)); });
            }
            if (!changed) { proceed(); return; }
            global.data.length = 0;
            out.forEach(function (l) { global.data.push(l); });
            if (typeof global.renderCart === 'function') global.renderCart();
            var msg = t('ui.js.batchPricesUpdated',
                'The batches this sale takes have changed its prices — the cart now shows what will be charged. Check it, then Complete Sale again.');
            showNote(msg);
            if (typeof global.showFormError === 'function') global.showFormError(msg);
        }).fail(function () { proceed(); });
    }

    $(document).on('change', '#sellBatchPick', onPick);

    global.PerBatchTill = { enabled: enabled, onBatches: onBatches, beforeAdd: beforeAdd,
        beforeComplete: beforeComplete, showNote: showNote };
})(window);
