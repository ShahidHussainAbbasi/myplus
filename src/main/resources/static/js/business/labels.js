/*
 * labels.js — U12: printing the shop's own labels.
 *
 * Design: microservices/docs/slices/u12-printing-labels.md
 *
 * U7 lets a shop register `LP-4471` to mean "one tablet". Nothing could PRINT that label, so the code had to
 * be written by hand — and a hand-written code cannot be scanned, which was the whole point of it.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * THE BARCODE IMAGE COMES FROM JsBarcode, DELIBERATELY
 *
 * A barcode is read by hardware the shop already owns. Correctness is not something to be clever about: an
 * established encoder has been read by millions of scanners, and a hand-rolled Code128 with a subtle checksum
 * error prints labels that look perfect and do not scan — discovered at the counter, by a queue.
 *
 * Vendored (js/business/JsBarcode.all.min.js), never a CDN: the CSP blocks external hosts, and this repo
 * already vendors pdfmake, jszip, DataTables and Chart the same way.
 *
 * ⚠ IF THE LIBRARY IS ABSENT THIS SCREEN REFUSES TO PRINT. It does not render empty boxes and let a shop
 * print a sheet of useless stickers — see `barcodeAvailable`.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * RENDERED AS HTML, PRINTED BY THE BROWSER
 *
 * Not pdfmake. A label sheet is a page of divs, and pdfmake is ~900KB — about 70% of what is left in the
 * bundle (PERF-4). It is not a tool to reach for casually.
 */
(function (global, $) {
    'use strict';

    /**
     * Sheet presets, as DATA.
     *
     * Every shop's stationery differs, and a hard-coded grid fits exactly one of them. `perPage` is derived
     * so the gate can assert it without measuring a rendered page.
     */
    var LAYOUTS = {
        'a4-3x8':  { label: 'A4 · 3 × 8',        cols: 3, rows: 8, w: '63mm',   h: '33mm' },
        'a4-2x7':  { label: 'A4 · 2 × 7',        cols: 2, rows: 7, w: '95mm',   h: '38mm' },
        'roll-40': { label: 'Roll · 40 × 25 mm', cols: 1, rows: 1, w: '40mm',   h: '25mm' }
    };

    var stickers = [];   // [{barcode, productId, soldUnit, quantity, productName, unitText, price}]

    function t(key, fallback) {
        return (typeof global.t === 'function' && global.t(key) !== key) ? global.t(key) : fallback;
    }

    /** Is the encoder actually loaded? A missing file must stop the print, not silently empty the labels. */
    function barcodeAvailable() { return typeof global.JsBarcode === 'function'; }

    global.showLabels = function () {
        $('.formDiv').hide();
        $('#LabelsDiv').show();
        if (!$('#lblLayout option').length) {
            var opts = Object.keys(LAYOUTS).map(function (k) {
                return '<option value="' + k + '">' + escHtml(LAYOUTS[k].label) + '</option>';
            }).join('');
            $('#lblLayout').html(opts);
        }
        loadStickers();
    };

    function loadStickers() {
        $('#lblBody').html('<tr><td colspan="5">' + t('ui.js.loading', 'Loading…') + '</td></tr>');
        $.get(serverContext + 'allProductBarcodes').done(function (resp) {
            var rows = (typeof apiList === 'function') ? apiList(resp) : (resp && resp.data) || [];
            // Names and prices come from the product list the screen already has a cheap read for.
            $.get(serverContext + 'getUserProduct?q=-1').done(function (presp) {
                var products = {};
                ((typeof apiList === 'function') ? apiList(presp) : (presp.collection || []))
                    .forEach(function (p) { products[String(p.id)] = p; });
                stickers = rows.map(function (b) {
                    var p = products[String(b.productId)] || {};
                    return {
                        id: b.id, barcode: b.barcode, productId: b.productId,
                        soldUnit: b.soldUnit, quantity: b.quantity,
                        productName: p.name || ('#' + b.productId),
                        unitText: (String(b.soldUnit).toUpperCase() === 'LOOSE')
                            ? (b.quantity + ' ' + (p.looseUnitPlural || p.looseUnit || ''))
                            : (b.quantity + ' ' + t('ui.packs', 'packs')),
                        price: p.sellingPrice
                    };
                });
                render();
            }).fail(render);
        }).fail(function () {
            $('#lblBody').html('<tr><td colspan="5">' + t('ui.js.couldNotLoadStickers') + '</td></tr>');
        });
    }

    function render() {
        if (!stickers.length) {
            // A product with no stickers is skipped, not printed blank — a blank label wastes stationery
            // and tells the shelf nothing.
            $('#lblBody').html('<tr><td colspan="5">' + t('ui.js.noStickersYet') + '</td></tr>');
            $('#lblPrint').prop('disabled', true);
            return;
        }
        var html = stickers.map(function (s) {
            return '<tr>'
                + '<td><input type="checkbox" class="lbl-pick" data-id="' + s.id + '"></td>'
                + '<td>' + escHtml(String(s.barcode)) + '</td>'
                + '<td>' + escHtml(String(s.productName)) + '</td>'
                + '<td>' + escHtml(String(s.unitText)) + '</td>'
                + '<td><input type="number" min="1" step="1" value="1" class="form-control lbl-qty" '
                + 'data-id="' + s.id + '" style="max-width:90px"></td>'
                + '</tr>';
        }).join('');
        $('#lblBody').html(html);
        $('#lblBody').off('change').on('change', '.lbl-pick', refreshPrintButton);
        refreshPrintButton();
    }

    function selected() {
        var picked = [];
        $('.lbl-pick:checked').each(function () {
            var id = $(this).data('id');
            var s = stickers.filter(function (x) { return String(x.id) === String(id); })[0];
            if (!s) return;
            var qty = Number($('.lbl-qty[data-id="' + id + '"]').val()) || 1;
            picked.push({ sticker: s, copies: Math.max(1, Math.floor(qty)) });
        });
        return picked;
    }

    function refreshPrintButton() {
        // Nothing selected prints nothing — the same rule as U11's Apply button.
        $('#lblPrint').prop('disabled', selected().length === 0);
    }

    global.selectAllLabels = function (on) {
        $('.lbl-pick').prop('checked', !!on);
        refreshPrintButton();
    };

    /**
     * Build the sheet and hand it to the browser's print dialog.
     *
     * ⚠ Refuses outright when the encoder is missing. Rendering the layout with empty barcode boxes would let
     * a shop print a whole sheet of stickers that cannot be scanned, and discover it at the counter.
     */
    global.printLabels = function () {
        var picked = selected();
        if (!picked.length) return;
        if (!barcodeAvailable()) {
            showFormError(t('ui.js.barcodeLibMissing',
                'The barcode library is not installed, so labels cannot be printed yet.'));
            return;
        }

        var layout = LAYOUTS[$('#lblLayout').val()] || LAYOUTS['a4-3x8'];
        var $sheet = $('#lblSheet').empty();

        picked.forEach(function (item) {
            for (var i = 0; i < item.copies; i++) $sheet.append(labelHtml(item.sticker, layout));
        });

        // Draw every barcode AFTER the nodes are in the document — JsBarcode measures the element.
        $sheet.find('svg.lbl-code').each(function () {
            try {
                global.JsBarcode(this, String($(this).data('code')), {
                    format: 'CODE128', displayValue: false, height: 34, width: 1.4, margin: 0
                });
            } catch (e) {
                // One bad code must not abandon the sheet; the text line below it still identifies the item.
                $(this).replaceWith('<div class="lbl-codefail">' + escHtml(String($(this).data('code'))) + '</div>');
            }
        });

        global.print();
    };

    function labelHtml(s, layout) {
        var price = (s.price != null && s.price !== '') ? Number(s.price).toFixed(2) : '';
        return '<div class="lbl" style="width:' + layout.w + ';height:' + layout.h + '">'
            + '<div class="lbl-name">' + escHtml(String(s.productName)) + '</div>'
            + '<div class="lbl-meta"><span>' + escHtml(String(s.unitText)) + '</span>'
            + '<span class="lbl-price">' + escHtml(price) + '</span></div>'
            + '<svg class="lbl-code" data-code="' + escHtml(String(s.barcode)) + '"></svg>'
            // ⭐ The code as TEXT as well as bars: a label that will not scan is then still keyable, which
            // turns a bad print into a slow sale rather than a dead product.
            + '<div class="lbl-text">' + escHtml(String(s.barcode)) + '</div>'
            + '</div>';
    }

    /**
     * The no-dependency path, shipped alongside: hand the codes to whatever the shop already prints with.
     *
     * Useful on its own — many shops own Dymo or Zebra software, or a barcode font in a spreadsheet — and it
     * means a printer this layout does not suit is not a dead end.
     */
    global.exportLabelsCsv = function () {
        var rows = [['barcode', 'product', 'soldUnit', 'quantity', 'price']];
        stickers.forEach(function (s) {
            rows.push([s.barcode, s.productName, s.soldUnit, s.quantity,
                s.price == null ? '' : s.price]);
        });
        var csv = rows.map(function (r) {
            return r.map(function (c) {
                var v = String(c == null ? '' : c);
                return /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
            }).join(',');
        }).join('\n');

        var blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
        var a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = 'stickers.csv';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    };

    /** Exported for the gate: how many labels a layout fits on a page, without measuring a rendered one. */
    global.labelsPerPage = function (key) {
        var l = LAYOUTS[key];
        return l ? l.cols * l.rows : 0;
    };

    /** Exported for the gate: is the encoder present? */
    global.labelBarcodeReady = barcodeAvailable;
})(window, jQuery);
