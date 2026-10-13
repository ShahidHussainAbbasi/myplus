/*
 * ssr.js — SSR-1: the Stock & Sales Report (design: microservices/docs/slices/110-stock-ledger-ssr.md).
 *
 * Per product for a period, grouped by MAIN SUPPLIER, with subtotals and a Net Total — the report a pharmacy sends each
 * supplier. Quantities come from the stock ledger (so every row adds up: Closing = Opening + Purchase − Purchase return
 * − Net sales − Bonus + Adjustments); money and the bonus split from the sale documents; TP = the last purchase rate.
 *
 * On screen, as a landscape PDF (the shared lazy pdfmake, never a second copy), and as CSV. Owner/admin only — the
 * menu entry sits in the owner/admin Finance group and business-service refuses anyone else.
 */
(function (global, $) {
    'use strict';

    var last = null;   // the last report loaded: PDF and CSV print exactly what is on screen

    function tr(key, fallback) {
        var a = Array.prototype.slice.call(arguments, 2);
        var s = (typeof global.t === 'function' && global.t(key) !== key) ? global.t(key) : fallback;
        return String(s).replace(/\{(\d+)\}/g, function (m, n) { return a[n] == null ? m : String(a[n]); });
    }
    var esc = function (v) { return (typeof global.escHtml === 'function') ? global.escHtml(v) : String(v); };

    /** Quantities: whole numbers print whole, fractions (loose stock) to 2 places; zero prints blank, as the PDF does. */
    function qty(v) {
        var n = Number(v);
        if (!isFinite(n) || n === 0) return '';
        return (Math.round(n) === n ? n : Math.round(n * 100) / 100).toLocaleString('en-US');
    }
    function money(v) {
        var n = Number(v);
        if (!isFinite(n) || n === 0) return '';
        return Math.round(n).toLocaleString('en-US');
    }

    /** The columns, as data: header, key, formatter — one list drives screen, PDF and CSV alike. */
    function columns() {
        return [
            { h: tr('ui.js.ssrCode', 'Code'), k: 'code', f: function (v) { return v == null ? '' : String(v); }, text: true },
            { h: tr('ui.js.ssrItem', 'Item Name'), k: 'name', f: function (v) { return v == null ? '' : String(v); }, text: true },
            { h: tr('ui.js.ssrPack', 'Pack'), k: 'pack', f: function (v) { return v == null ? '' : String(v); }, text: true },
            { h: tr('ui.js.ssrTp', 'TP'), k: 'tp', f: function (v) { var n = Number(v); return n ? n.toFixed(2) : ''; } },
            { h: tr('ui.js.ssrOpening', 'Opening Stock Qty'), k: 'opening', f: qty, sum: true },
            { h: tr('ui.js.ssrPurchase', 'Purchase Qty'), k: 'purchase', f: qty, sum: true },
            { h: tr('ui.js.ssrPurchaseReturn', 'Purchase Return Qty'), k: 'purchaseReturn', f: qty, sum: true },
            { h: tr('ui.js.ssrTotalStock', 'Total Stock Qty'), k: 'totalStock', f: qty, sum: true },
            { h: tr('ui.js.ssrSalesQty', 'Sales Qty'), k: 'salesQty', f: qty, sum: true },
            { h: tr('ui.js.ssrSalesValue', 'Sales Value'), k: 'salesValue', f: money, sum: true },
            { h: tr('ui.js.ssrReturnQty', 'Sales Return Qty'), k: 'salesReturnQty', f: qty, sum: true },
            { h: tr('ui.js.ssrReturnValue', 'Sales Return Value'), k: 'salesReturnValue', f: money, sum: true },
            { h: tr('ui.js.ssrNetQty', 'Net Sales Qty'), k: 'netSalesQty', f: qty, sum: true },
            { h: tr('ui.js.ssrNetValue', 'Net Sales Value'), k: 'netSalesValue', f: money, sum: true },
            { h: tr('ui.js.ssrBonusQty', 'Bonus Qty'), k: 'bonusQty', f: qty, sum: true },
            { h: tr('ui.js.ssrBonusValue', 'Bonus Value'), k: 'bonusValue', f: money, sum: true },
            { h: tr('ui.js.ssrAdjustments', 'Adjust Qty'), k: 'adjustments', f: qty, sum: true, optional: true },
            { h: tr('ui.js.ssrClosingQty', 'Closing Qty'), k: 'closingQty', f: qty, sum: true },
            { h: tr('ui.js.ssrClosingValue', 'Closing Value'), k: 'closingValue', f: money, sum: true }
        ];
    }

    /** The Adjust column shows only when something in the report was adjusted — so every row can add up. */
    function visibleColumns(rep) {
        var anyAdj = rep && rep.netTotal && Number(rep.netTotal.adjustments) !== 0;
        if (!anyAdj && rep && rep.groups) {
            rep.groups.forEach(function (g) { (g.rows || []).forEach(function (r) { if (Number(r.adjustments)) anyAdj = true; }); });
        }
        return columns().filter(function (c) { return !c.optional || anyAdj; });
    }

    function today() {
        var d = new Date();
        return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
    }
    function monthStart() { return today().slice(0, 8) + '01'; }

    global.showSSR = function () {
        $('.formDiv').hide();
        $('#SSRDiv').show();
        if (!$('#ssrFrom').val()) $('#ssrFrom').val(monthStart());
        if (!$('#ssrTo').val()) $('#ssrTo').val(today());
        var $sup = $('#ssrSupplier');
        if (!$sup.data('loaded')) {
            $sup.data('loaded', true);
            $.get(serverContext + 'getUserVenders', function (html) {
                // the endpoint's own blank option is dropped: this picker has "All suppliers" already
                $sup.find('option:not(:first)').remove();
                $(String(html)).filter('option').each(function () { if (this.value) $sup.append($(this).clone()); });
                if (typeof global.refreshSearchableSelect === 'function') global.refreshSearchableSelect($sup);
            });
        }
        var $co = $('#ssrCompany');
        if (!$co.data('loaded')) {
            $co.data('loaded', true);
            $.get(serverContext + 'getUserCompanies', function (html) {
                $co.find('option:not(:first)').remove();
                $(String(html)).filter('option').each(function () { if (this.value) $co.append($(this).clone()); });
                if (typeof global.refreshSearchableSelect === 'function') global.refreshSearchableSelect($co);
            });
        }
    };

    global.loadSSR = function () {
        var params = { from: $('#ssrFrom').val(), to: $('#ssrTo').val() };
        if ($('#ssrSupplier').val()) params.supplierId = $('#ssrSupplier').val();
        if ($('#ssrCompany').val()) params.companyId = $('#ssrCompany').val();
        $('#ssrBody').html('<tr><td colspan="19">' + esc(tr('ui.js.loading', 'Loading…')) + '</td></tr>');
        $('#ssrPdf,#ssrCsv').prop('disabled', true);
        $.ajax({ url: serverContext + 'stockSalesReport', data: params, dataType: 'json' }).done(function (rep) {
            if (!rep || rep.success === false) {
                last = null;
                $('#ssrBody').html('<tr><td colspan="19" class="text-danger">' + esc((rep && rep.message)
                    || tr('ui.js.ssrFailed', 'The report could not be loaded.')) + '</td></tr>');
                return;
            }
            last = rep;
            render(rep);
        }).fail(function () {
            last = null;
            $('#ssrBody').html('<tr><td colspan="19" class="text-danger">' + esc(tr('ui.js.ssrFailed', 'The report could not be loaded.')) + '</td></tr>');
        });
    };

    function render(rep) {
        var cols = visibleColumns(rep);
        $('#ssrHead').html('<tr>' + cols.map(function (c) { return '<th>' + esc(c.h) + '</th>'; }).join('') + '</tr>');
        // Periods before the ledger started cannot be reported: say so rather than print zeros as if they were facts.
        var start = rep.ledgerStart;
        $('#ssrNotice').toggle(!!start && rep.from < start).text(start && rep.from < start
            ? tr('ui.js.ssrBeforeLedger', 'Stock movements are recorded from {0}; figures before that day are not available.', start) : '');
        var html = [];
        var groups = rep.groups || [];
        if (!groups.length) {
            html.push('<tr><td colspan="' + cols.length + '" class="text-muted">' + esc(tr('ui.js.ssrEmpty', 'No stock moved in this period.')) + '</td></tr>');
        }
        groups.forEach(function (g) {
            html.push('<tr class="ssr-group"><td colspan="' + cols.length + '">' + esc(g.supplier) + '</td></tr>');
            (g.rows || []).forEach(function (r) {
                html.push('<tr class="ssr-row' + (r.adds === false ? ' ssr-warn' : '') + '" data-product="' + esc(r.productId)
                    + '"' + (r.adds === false ? ' title="' + esc(tr('ui.js.ssrNotAdding', 'The sale lines\' bonus does not match the goods that left; check this product.')) + '"' : '') + '>'
                    + cols.map(function (c) {
                        return '<td' + (c.text ? '' : ' class="text-right"') + ' data-k="' + c.k + '">' + esc(c.f(r[c.k])) + '</td>';
                    }).join('') + '</tr>');
            });
            html.push(totalRow(cols, g.total, tr('ui.js.ssrTotal', 'Total:'), 'ssr-subtotal'));
        });
        if (groups.length) html.push(totalRow(cols, rep.netTotal, tr('ui.js.ssrNetTotal', 'Net Total:'), 'ssr-nettotal'));
        $('#ssrBody').html(html.join(''));
        $('#ssrPdf,#ssrCsv').prop('disabled', !groups.length);
    }

    function totalRow(cols, t, label, cls) {
        var firstSum = cols.findIndex(function (c) { return c.sum; });
        var cells = ['<td colspan="' + firstSum + '" class="text-right">' + esc(label) + '</td>'];
        cols.slice(firstSum).forEach(function (c) {
            cells.push('<td class="text-right" data-k="' + c.k + '">' + esc(c.sum ? c.f(t && t[c.k]) : '') + '</td>');
        });
        return '<tr class="' + cls + '">' + cells.join('') + '</tr>';
    }

    function title(rep) {
        return tr('ui.js.ssrTitle', 'Stock With Sales Report') + ' — ' + rep.from + ' ' + tr('ui.js.ssrTo', 'to') + ' ' + rep.to;
    }

    global.printSSR = function () {
        if (!last) return;
        if (!global.LazyExport || typeof global.LazyExport.ensurePdfMake !== 'function') {
            return global.showFormError(tr('ui.js.pdfUnavailable', 'PDF export is not available.'));
        }
        var rep = last, cols = visibleColumns(rep);
        $('#ssrPdf').prop('disabled', true);
        global.LazyExport.ensurePdfMake().then(function () {
            var body = [cols.map(function (c) { return { text: c.h, style: 'th' }; })];
            var firstSum = cols.findIndex(function (c) { return c.sum; });
            var totalLine = function (t, label) {
                var row = [{ text: label, colSpan: firstSum, style: 'th', alignment: 'right' }];
                for (var i = 1; i < firstSum; i++) row.push('');
                cols.slice(firstSum).forEach(function (c) { row.push({ text: c.f(t && t[c.k]), style: 'th', alignment: 'right' }); });
                return row;
            };
            (rep.groups || []).forEach(function (g) {
                var head = [{ text: g.supplier, colSpan: cols.length, style: 'group', alignment: 'center' }];
                for (var i = 1; i < cols.length; i++) head.push('');
                body.push(head);
                (g.rows || []).forEach(function (r) {
                    body.push(cols.map(function (c) { return { text: c.f(r[c.k]), alignment: c.text ? 'left' : 'right' }; }));
                });
                body.push(totalLine(g.total, tr('ui.js.ssrTotal', 'Total:')));
            });
            body.push(totalLine(rep.netTotal, tr('ui.js.ssrNetTotal', 'Net Total:')));
            var biz = $.trim($('#businessName').text() || $('.app-sidebar__brand').text() || '');
            global.pdfMake.createPdf({
                pageOrientation: 'landscape', pageSize: 'A4', pageMargins: [18, 24, 18, 28],
                defaultStyle: { fontSize: 6.5 },
                footer: function (page, pages) {
                    return { columns: [{ text: new Date().toLocaleString(), margin: [18, 0] },
                        { text: tr('ui.js.ssrPage', 'Page {0} of {1}', page, pages), alignment: 'right', margin: [0, 0, 18, 0] }], fontSize: 7 };
                },
                content: [
                    { text: biz, style: 'h1', alignment: 'center' },
                    { text: title(rep), style: 'h2', alignment: 'center', margin: [0, 2, 0, 6] },
                    { table: { headerRows: 1, widths: cols.map(function (c) { return c.k === 'name' ? '*' : 'auto'; }), body: body },
                      layout: 'lightHorizontalLines' }
                ],
                styles: { h1: { fontSize: 11, bold: true }, h2: { fontSize: 8, bold: true }, th: { bold: true }, group: { bold: true, fontSize: 7.5 } }
            }).download('stock-sales-' + rep.from + '-' + rep.to + '.pdf');
        }).catch(function () {
            global.showFormError(tr('ui.js.pdfUnavailable', 'PDF export is not available.'));
        }).finally(function () { $('#ssrPdf').prop('disabled', false); });
    };

    global.exportSSRCsv = function () {
        if (!last) return;
        var rep = last, cols = visibleColumns(rep);
        var lines = [['Supplier'].concat(cols.map(function (c) { return c.h; }))];
        (rep.groups || []).forEach(function (g) {
            (g.rows || []).forEach(function (r) {
                lines.push([g.supplier].concat(cols.map(function (c) { return r[c.k] == null ? '' : r[c.k]; })));
            });
        });
        var csv = lines.map(function (r) {
            return r.map(function (c) {
                var v = String(c == null ? '' : c);
                return /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
            }).join(',');
        }).join('\n');
        var a = document.createElement('a');
        a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8;' }));
        a.download = 'stock-sales-' + rep.from + '-' + rep.to + '.csv';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    };

    /** Exported for the gate: the report as last loaded. */
    global.ssrLastReport = function () { return last; };
})(window, jQuery);
