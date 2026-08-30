/*
 * stock-count.js — U11: counting the shelf.
 *
 * Design: microservices/docs/slices/u11-counting-the-shelf.md
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * WHAT THIS IS, AND WHAT IT IS NOT
 *
 * The ADJUSTING already existed: /adjustProductStock writes a StockAdjustment carrying the quantity, the
 * direction, the reason, who did it and when. What did not exist was a way to write down what you counted.
 *
 * So there is no count-session table and no approval step. `StockAdjustment` IS the record — a second table
 * would be a second record of the same fact, and the day the two disagree the shop has two answers and no
 * way to choose. Approval before applying is a real requirement for a large shop; it is a separate slice, to
 * be built when someone asks, not guessed at now.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * THE THREE RULES THAT MATTER
 *
 *   1. A BLANK ROW IS NOT A ZERO. A count sheet is almost never finished in one pass, and treating
 *      "not counted yet" as "counted zero" would wipe the shelf.
 *
 *   2. A ROW WHOSE SYSTEM QUANTITY MOVED WHILE COUNTING IS FLAGGED, NOT APPLIED. A sheet is filled over
 *      minutes or hours while the shop keeps selling; applying a variance computed against a number that has
 *      since moved would CREATE the discrepancy it was meant to fix.
 *
 *   3. PARTIAL APPLICATION IS REPORTED, NOT HIDDEN. Each product is its own adjustment; there is no
 *      transaction across them. Pretending otherwise would be the worse lie.
 */
(function (global, $) {
    'use strict';

    /** productId -> {name, packSize, unit, system, countedPacks, countedPieces} */
    var sheet = {};

    function t(key, fallback) {
        return (typeof global.t === 'function' && global.t(key) !== key) ? global.t(key) : fallback;
    }

    function num(v) { var n = Number(v); return isFinite(n) ? n : 0; }

    /** A product's on-hand expressed in PIECES, so a variance is a whole number the counter recognises. */
    function toPieces(qty, packSize) {
        return (packSize > 1) ? Math.round(num(qty) * packSize) : num(qty);
    }

    /** What the counter typed, in pieces. `null` when they have not filled this row at all (rule 1). */
    function countedPieces(row) {
        var p = $('#cntPacks_' + row.id).val();
        var q = $('#cntPieces_' + row.id).val();
        var packsBlank = (p === undefined || String(p).trim() === '');
        var piecesBlank = (q === undefined || String(q).trim() === '');
        if (packsBlank && piecesBlank) return null;          // untouched — not a zero
        if (row.packSize > 1) return num(p) * row.packSize + num(q);
        return num(p);
    }

    global.showStockCount = function () {
        $('.formDiv').hide();
        $('#StockCountDiv').show();
        loadSheet();
    };

    function loadSheet() {
        sheet = {};
        $('#cntBody').html('<tr><td colspan="5">' + t('ui.js.loading', 'Loading…') + '</td></tr>');
        $('#cntSummary').empty();

        // One read for the whole catalogue — the same call the product grid already makes.
        $.get(serverContext + 'getUserProduct?q=-1').done(function (presp) {
            var products = (typeof apiList === 'function') ? apiList(presp) : (presp.collection || []);
            $.get(serverContext + 'productStockLevels').done(function (lresp) {
                var levels = (lresp && lresp.levels) || {};
                render(products, levels);
            }).fail(function () { render(products, {}); });
        }).fail(function () {
            $('#cntBody').html('<tr><td colspan="5">' + t('ui.js.couldNotLoadTheCount') + '</td></tr>');
        });
    }

    function render(products, levels) {
        var rows = [];
        products.forEach(function (p) {
            var lvl = levels[String(p.id)];
            var system = lvl ? num(lvl.onHand) : 0;
            var packSize = num(p.packSize);
            sheet[p.id] = {
                id: p.id, name: p.name, packSize: packSize,
                unit: p.looseUnitPlural || p.looseUnit || '',
                system: system
            };
            rows.push(rowHtml(sheet[p.id]));
        });
        $('#cntBody').html(rows.join('') || '<tr><td colspan="5">' + t('ui.js.noProducts', 'No products.') + '</td></tr>');
        $('#cntBody').off('input').on('input', 'input', function () {
            var id = String(this.id).split('_')[1];
            recalcRow(sheet[id]);
            recalcSummary();
        });
        recalcSummary();
    }

    function rowHtml(row) {
        // shelfText is U6's renderer: "9 + 5 tablets" for a divisible product, a plain number otherwise.
        var systemText = (typeof shelfText === 'function')
            ? shelfText(row.system, row.packSize, row.unit).text
            : String(row.system);

        var boxes = (row.packSize > 1)
            ? '<input type="number" step="1" min="0" class="form-control cnt-box" id="cntPacks_' + row.id + '" '
              + 'placeholder="' + escHtml(t('ui.packs', 'packs')) + '"> '
              + '<input type="number" step="1" min="0" class="form-control cnt-box" id="cntPieces_' + row.id + '" '
              + 'placeholder="' + escHtml(row.unit || t('ui.piece', 'pieces')) + '">'
            : '<input type="number" step="any" min="0" class="form-control cnt-box" id="cntPacks_' + row.id + '">';

        return '<tr id="cntRow_' + row.id + '">'
            + '<td>' + escHtml(String(row.name || '')) + '</td>'
            + '<td class="text-right cnt-system">' + escHtml(systemText) + '</td>'
            + '<td>' + boxes + '</td>'
            + '<td class="text-right" id="cntVar_' + row.id + '">—</td>'
            + '<td id="cntNote_' + row.id + '"></td>'
            + '</tr>';
    }

    function recalcRow(row) {
        if (!row) return;
        var counted = countedPieces(row);
        var $var = $('#cntVar_' + row.id);
        if (counted === null) { $var.text('—').removeClass('text-danger text-success'); return; }
        var systemPieces = toPieces(row.system, row.packSize);
        var diff = counted - systemPieces;
        var unit = (row.packSize > 1) ? (' ' + (row.unit || '')) : '';
        $var.text(diff === 0 ? '—' : (diff > 0 ? '+' : '') + diff + unit)
            .toggleClass('text-danger', diff < 0)
            .toggleClass('text-success', diff > 0);
    }

    function varyingRows() {
        var out = [];
        Object.keys(sheet).forEach(function (id) {
            var row = sheet[id];
            var counted = countedPieces(row);
            if (counted === null) return;                                  // rule 1: untouched
            var diff = counted - toPieces(row.system, row.packSize);
            if (diff === 0) return;                                        // nothing to write
            out.push({ row: row, counted: counted, diff: diff });
        });
        return out;
    }

    function recalcSummary() {
        var v = varyingRows();
        $('#cntSummary').text(v.length
            ? t('ui.js.countRowsToAdjust', 'Rows to adjust: ') + v.length
            : '');
        $('#cntApply').prop('disabled', v.length === 0);
    }

    global.applyStockCount = function () {
        var v = varyingRows();
        if (!v.length) return;

        /*
         * uiConfirm takes an OPTIONS OBJECT and returns a PROMISE — not (message, callback).
         * Calling it the other way sets `.input` on a string, which throws inside the dialog and leaves the
         * button dead: the cashier clicks Apply and nothing happens, with the error only in the console.
         */
        uiConfirm({
            title: t('ui.stockCount', 'Stock count'),
            message: t('ui.js.countConfirm', 'Adjust stock for ') + v.length + ' '
                + t('ui.js.countConfirmProducts', 'product(s)?'),
            confirmText: t('ui.applyCount', 'Apply count'),
            tone: 'danger'
        }).then(function (ok) {
            if (ok) applyAll(v);
        });
    };

    /**
     * ⚠ Re-read on-hand FIRST, and skip any row whose system quantity moved while the sheet was open.
     *
     * A count is filled over minutes or hours while the shop keeps selling. The variance on screen was
     * computed against the number this sheet loaded with; if that number has since changed, applying the
     * difference would CREATE a discrepancy rather than correct one. Flagged for a recount, never guessed.
     */
    function applyAll(varying) {
        $('#cntApply').prop('disabled', true);
        $.get(serverContext + 'productStockLevels').done(function (lresp) {
            var levels = (lresp && lresp.levels) || {};
            var applied = 0, moved = 0, failed = 0;
            var pending = varying.length;

            varying.forEach(function (item) {
                var row = item.row;
                var liveSystem = levels[String(row.id)] ? num(levels[String(row.id)].onHand) : 0;
                if (Math.abs(liveSystem - row.system) > 0.00005) {
                    moved++;
                    $('#cntNote_' + row.id).text(t('ui.js.countMoved', 'Stock changed while counting — recount'))
                        .addClass('text-danger');
                    if (--pending === 0) report(applied, moved, failed);
                    return;
                }
                // The adjustment is in the STOCK unit (packs), because that is what inventory stores.
                var diffPacks = (row.packSize > 1) ? (item.diff / row.packSize) : item.diff;
                $.ajax({
                    type: 'POST', url: serverContext + 'adjustProductStock',
                    contentType: 'application/json', dataType: 'json',
                    data: JSON.stringify({
                        productId: Number(row.id),
                        adjustmentType: item.diff > 0 ? 'INCREASE' : 'DECREASE',
                        quantity: Math.abs(diffPacks),
                        reason: t('ui.js.countReason', 'Stock count ') + new Date().toISOString().slice(0, 10)
                    }),
                    success: function (resp) {
                        if (typeof apiOk === 'function' && !apiOk(resp)) {
                            failed++;
                            $('#cntNote_' + row.id).text(apiMessage(resp, t('ui.js.countFailedRow', 'Not adjusted')))
                                .addClass('text-danger');
                        } else {
                            applied++;
                            // Update the row IN PLACE: the shelf now holds what was counted, so that becomes
                            // the new system quantity and the variance returns to nothing.
                            row.system = (row.packSize > 1) ? (item.counted / row.packSize) : item.counted;
                            $('#cntRow_' + row.id).find('.cnt-system').text(
                                (typeof shelfText === 'function')
                                    ? shelfText(row.system, row.packSize, row.unit).text
                                    : String(row.system));
                            $('#cntPacks_' + row.id).val('');
                            $('#cntPieces_' + row.id).val('');
                            recalcRow(row);
                            $('#cntNote_' + row.id).text(t('ui.js.countApplied', 'Adjusted')).removeClass('text-danger');
                        }
                        if (--pending === 0) report(applied, moved, failed);
                    },
                    error: function (xhr) {
                        failed++;
                        $('#cntNote_' + row.id).text(typeof apiFailMessage === 'function'
                            ? apiFailMessage(xhr, t('ui.js.countFailedRow', 'Not adjusted'))
                            : t('ui.js.countFailedRow', 'Not adjusted')).addClass('text-danger');
                        if (--pending === 0) report(applied, moved, failed);
                    }
                });
            });
        }).fail(function () {
            $('#cntApply').prop('disabled', false);
            showFormError(t('ui.js.couldNotLoadTheCount'));
        });
    }

    /**
     * ⚠ Report every outcome, including the partial one — and DO NOT RELOAD THE SHEET.
     *
     * The first version reloaded and re-applied the per-row notes after a fixed 600 ms. `loadSheet` is two
     * chained async reads, so whenever its render landed after that timer the notes were wiped and the
     * operator was shown a blank result for work that had actually been done. <b>A fixed delay is a guess
     * about someone else's latency</b>, and one in product code is worse than one in a test.
     *
     * Each applied row is updated in place instead: its system quantity becomes what was counted, its boxes
     * clear, its variance returns to nothing. Failures keep their note and their numbers, so they can be
     * retried — and a long sheet does not rebuild itself under the person reading it.
     *
     * There is no transaction across products — each adjustment stands alone. A summary that said "done"
     * while three rows silently failed would leave a shop believing its shelf matched the system.
     */
    function report(applied, moved, failed) {
        var parts = [t('ui.js.countApplied', 'Adjusted') + ': ' + applied];
        if (moved) parts.push(t('ui.js.countMovedCount', 'changed while counting') + ': ' + moved);
        if (failed) parts.push(t('ui.js.countFailedCount', 'not adjusted') + ': ' + failed);
        var text = parts.join(' · ');
        if (moved || failed) showFormError(text); else showSaleSuccess(text);
        recalcSummary();
        $('#cntApply').prop('disabled', varyingRows().length === 0);
    }


    /** Exported for the gate: the arithmetic, without a browser round trip. */
    global.stockCountVariance = function (systemQty, packSize, countedPacks, countedPieces) {
        var systemPieces = toPieces(systemQty, packSize);
        var counted = (packSize > 1)
            ? num(countedPacks) * packSize + num(countedPieces)
            : num(countedPacks);
        return counted - systemPieces;
    };
})(window, jQuery);
