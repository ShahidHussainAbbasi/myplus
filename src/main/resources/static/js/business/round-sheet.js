/*
 * OMS O8 slice 2 — the delivery round's RECOVERY SHEET: view, print, download.
 *
 * ── What this replaces ─────────────────────────────────────────────────────────────────────────────────────
 *
 * A printed Net Sales Summary, covered in handwriting down the right margin: "CR" where a shop took the goods
 * on credit, an amount where the salesman collected one. The pen was recording the collection because the
 * sheet had nowhere to record it. So Received, Balance and Signature are printed columns here, deliberately
 * blank — the salesman still writes in them, but on ruled lines that add up.
 *
 * ── Two rules this file follows ────────────────────────────────────────────────────────────────────────────
 *
 * 1. THE TOTALS COME FROM THE SERVER. The foot of this sheet is what the cash bag is counted against, and a
 *    total summed from rendered rows can silently disagree with what the server actually sent — a row that
 *    failed to draw, a rounding step, a filter. `stopCount`, `invoiceTotal` and `totalDue` arrive with the
 *    rows and are printed as received.
 *
 * 2. NO SECOND RENDERER. Print reuses the same hidden-iframe approach receipt.js uses, and the PDF reuses
 *    LazyExport.ensurePdfMake — the loader the grid exports already use. Neither pdfmake nor a print stylesheet
 *    is restated here.
 */
(function (global, $) {
    'use strict';

    var state = { sheet: null, keying: false };

    function esc(s) {
        return (global.escHtml ? global.escHtml(String(s == null ? '' : s)) : String(s == null ? '' : s));
    }

    function tr(key, fallback) {
        return (typeof global.t === 'function' && typeof global.tHas === 'function' && global.tHas(key))
            ? global.t(key) : fallback;
    }

    function money(v) {
        return v == null ? '' : Number(v).toFixed(2);
    }

    /** The picker's wire format → ISO. Owned by /js/common/date-picker.js, which owns the format itself. */
    function toIso(v) { return global.dpToIso ? global.dpToIso(v) : ''; }

    global.showRoundSheet = function () {
        $('.formDiv').hide();
        $('#RoundSheetDiv').show();
        // Not auto-loaded. This screen makes a remote call per stop's worth of balances, and opening a menu is
        // not the same as asking for today's round — the operator presses Load.
        $('#rsBody').empty();
        $('#rsFoot').empty();
        $('#rsHeading').empty();
        $('#rsEmpty').hide();
        state.sheet = null;
    };

    global.loadRoundSheet = function () {
        var params = {};
        var from = toIso($('#rsFrom').val()); if (from) { params.from = from; }
        var to = toIso($('#rsTo').val()); if (to) { params.to = to; }
        var salesman = $.trim($('#rsSalesman').val() || ''); if (salesman) { params.salesman = salesman; }

        $('#rsLoadBtn').prop('disabled', true);
        $.get(serverContext + 'roundSheet', params, function (resp) {
            $('#rsLoadBtn').prop('disabled', false);
            if (!resp || resp.success === false) {
                // Relay the server's own words. When the balances cannot be read the sheet is refused ON
                // PURPOSE, and that reason is the one thing the operator must see — a generic failure would
                // have them retrying a print that is being declined deliberately.
                return global.showFormError((resp && resp.message)
                    || tr('ui.js.couldNotLoadRoundSheet', 'Could not load the round sheet.'));
            }
            state.sheet = resp.data || null;
            render();
        }).fail(function (xhr) {
            $('#rsLoadBtn').prop('disabled', false);
            global.showFormError((xhr.responseJSON && xhr.responseJSON.message)
                || tr('ui.js.couldNotLoadRoundSheet', 'Could not load the round sheet.'));
        });
    };

    function render() {
        var s = state.sheet;
        var stops = (s && s.stops) || [];
        $('#rsEmpty').toggle(stops.length === 0);

        $('#rsHeading').text(
            (s && s.salesman ? tr('ui.salesman', 'Salesman') + ': ' + s.salesman + '  ·  ' : '')
            + tr('ui.from', 'From') + ' ' + (s ? s.from : '') + '  ' + tr('ui.to', 'To') + ' ' + (s ? s.to : ''));

        var html = '';
        stops.forEach(function (r) {
            html += '<tr>'
                + '<td>' + esc(r.sr) + '</td>'
                + '<td>' + esc(r.invoiceNo) + '</td>'
                + '<td>' + esc(r.accountName) + '</td>'
                + '<td>' + esc(r.area) + '</td>'
                + '<td class="text-right">' + money(r.invoiceTotal) + '</td>'
                + '<td class="text-right">' + money(r.previousBalance) + '</td>'
                + '<td class="text-right"><b>' + money(r.totalDue) + '</b></td>'
                // Going OUT: blank, deliberately — the handwriting, given a ruled line.
                // Coming BACK: the same cell becomes the box the marked-up amount is typed into, so the
                // operator reads down the paper and types down the screen with nothing to cross-reference.
                + (state.keying
                    ? '<td class="text-right"><input type="number" step="0.01" min="0"'
                      + ' class="form-control input-sm rs-received" style="width:110px;text-align:right"'
                      + ' data-order="' + Number(r.orderId || 0) + '"'
                      + ' data-due="' + money(r.totalDue) + '"'
                      + ' oninput="recalcRoundDeclared()"></td>'
                    : '<td class="text-right">&nbsp;</td>')
                + '<td class="text-right">&nbsp;</td>'
                + '<td>&nbsp;</td>'
                + '</tr>';
        });
        $('#rsBody').html(html);

        // Straight from the server. See rule 1 in the file header.
        if (s && stops.length) {
            $('#rsFoot').html('<tr>'
                + '<th colspan="4" class="text-right">'
                + esc(tr('ui.stops', 'Stops') + ': ' + s.stopCount) + '</th>'
                + '<th class="text-right">' + money(s.invoiceTotal) + '</th>'
                + '<th></th>'
                + '<th class="text-right">' + money(s.totalDue) + '</th>'
                + '<th colspan="3"></th>'
                + '</tr>');
        } else {
            $('#rsFoot').empty();
        }
    }

    // ── the printable document ─────────────────────────────────────────────────────────────────────────────

    /**
     * The sheet as a standalone document.
     *
     * <p>Built once and used by BOTH print and PDF, so the paper a salesman carries and the file a manager
     * keeps cannot differ. The signature strip at the foot is what makes this a control rather than a listing:
     * the salesman signs for the cash he is carrying and the cashier signs for what came back.
     */
    function documentHtml() {
        var s = state.sheet;
        if (!s || !(s.stops || []).length) { return null; }

        var rows = s.stops.map(function (r) {
            return '<tr>'
                + '<td>' + esc(r.sr) + '</td>'
                + '<td>' + esc(r.invoiceNo) + '</td>'
                + '<td>' + esc(r.accountName) + '</td>'
                + '<td>' + esc(r.area) + '</td>'
                + '<td class="r">' + money(r.invoiceTotal) + '</td>'
                + '<td class="r">' + money(r.previousBalance) + '</td>'
                + '<td class="r b">' + money(r.totalDue) + '</td>'
                + '<td class="r blank"></td><td class="r blank"></td><td class="blank"></td>'
                + '</tr>';
        }).join('');

        return '<!doctype html><html><head><meta charset="utf-8"><title>'
            + esc(tr('ui.roundSheet', 'Round sheet')) + '</title><style>'
            + 'body{font-family:Arial,Helvetica,sans-serif;font-size:11px;margin:14px}'
            + 'h2{margin:0;font-size:16px;text-align:center}'
            + '.sub{text-align:center;margin:2px 0 10px;font-size:11px}'
            + 'table{width:100%;border-collapse:collapse}'
            + 'th,td{border:1px solid #333;padding:4px 5px}'
            + 'th{background:#eee;font-size:10px;text-align:left}'
            + '.r{text-align:right}.b{font-weight:bold}'
            + '.blank{height:18px}'                     /* room to write in */
            + 'tfoot th{background:#f6f6f6}'
            + '.sign{margin-top:22px;font-size:11px}'
            + '.sign div{display:inline-block;width:32%;border-top:1px solid #333;padding-top:4px;margin-right:1%}'
            + '@media print{body{margin:6mm}}'
            + '</style></head><body>'
            + '<h2>' + esc(tr('ui.roundSheet', 'Delivery & Recovery Sheet')) + '</h2>'
            + '<div class="sub">'
            + (s.salesman ? esc(tr('ui.salesman', 'Salesman') + ': ' + s.salesman) + ' &nbsp;·&nbsp; ' : '')
            + esc(s.from + ' — ' + s.to) + '</div>'
            + '<table><thead><tr>'
            + '<th>#</th><th>' + esc(tr('ui.invoice', 'Invoice')) + '</th>'
            + '<th>' + esc(tr('ui.account', 'Account')) + '</th>'
            + '<th>' + esc(tr('ui.area', 'Area')) + '</th>'
            + '<th class="r">' + esc(tr('ui.invoiceAmount', 'Invoice')) + '</th>'
            + '<th class="r">' + esc(tr('ui.previousBalance', 'Previous')) + '</th>'
            + '<th class="r">' + esc(tr('ui.totalDue', 'Total due')) + '</th>'
            + '<th class="r">' + esc(tr('ui.received', 'Received')) + '</th>'
            + '<th class="r">' + esc(tr('ui.balance', 'Balance')) + '</th>'
            + '<th>' + esc(tr('ui.signature', 'Signature')) + '</th>'
            + '</tr></thead><tbody>' + rows + '</tbody>'
            + '<tfoot><tr>'
            + '<th colspan="4" class="r">' + esc(tr('ui.stops', 'Stops') + ': ' + s.stopCount) + '</th>'
            + '<th class="r">' + money(s.invoiceTotal) + '</th><th></th>'
            + '<th class="r">' + money(s.totalDue) + '</th>'
            + '<th class="r blank"></th><th class="r blank"></th><th></th>'
            + '</tr></tfoot></table>'
            // The reason the sheet is a control and not a listing.
            + '<div class="sign">'
            + '<div>' + esc(tr('ui.received', 'Total received')) + '</div>'
            + '<div>' + esc(tr('ui.salesmanSignature', 'Salesman signature')) + '</div>'
            + '<div>' + esc(tr('ui.cashierSignature', 'Cashier signature')) + '</div>'
            + '</div>'
            + '</body></html>';
    }

    /** Print through a hidden iframe — the same approach receipt.js uses, so there is one print mechanism. */
    global.printRoundSheet = function () {
        var html = documentHtml();
        if (!html) { return global.showFormError(tr('ui.js.loadTheSheetFirst', 'Load the sheet first.')); }

        var frame = document.getElementById('rsPrintFrame');
        if (!frame) {
            frame = document.createElement('iframe');
            frame.id = 'rsPrintFrame';
            frame.style.position = 'fixed';
            frame.style.right = '0';
            frame.style.bottom = '0';
            frame.style.width = '0';
            frame.style.height = '0';
            frame.style.border = '0';
            document.body.appendChild(frame);
        }
        var doc = frame.contentWindow.document;
        doc.open();
        doc.write(html);
        doc.close();
        // Deferred a tick so the document has laid out before the (synchronous, blocking) print dialog opens.
        setTimeout(function () { frame.contentWindow.focus(); frame.contentWindow.print(); }, 60);
    };

    /**
     * Download as PDF.
     *
     * <p>Reuses {@code LazyExport.ensurePdfMake} — the same on-demand loader the grid exports use, so pdfmake
     * is fetched once, shared, and never bundled into the page for the many users who never print a round.
     * Built from the same {@code documentHtml()} the print path uses, expressed as a pdfmake table so the file
     * is real text rather than a screenshot: a manager needs to search it and a cashier needs to read the
     * figures.
     */
    global.downloadRoundSheet = function () {
        var s = state.sheet;
        if (!s || !(s.stops || []).length) {
            return global.showFormError(tr('ui.js.loadTheSheetFirst', 'Load the sheet first.'));
        }
        if (!global.LazyExport || typeof global.LazyExport.ensurePdfMake !== 'function') {
            return global.showFormError(tr('ui.js.pdfUnavailable', 'PDF export is not available.'));
        }

        $('#rsPdfBtn').prop('disabled', true);
        global.LazyExport.ensurePdfMake().then(function () {
            var head = ['#', tr('ui.invoice', 'Invoice'), tr('ui.account', 'Account'), tr('ui.area', 'Area'),
                tr('ui.invoiceAmount', 'Invoice'), tr('ui.previousBalance', 'Previous'),
                tr('ui.totalDue', 'Total due'), tr('ui.received', 'Received'),
                tr('ui.balance', 'Balance'), tr('ui.signature', 'Signature')]
                .map(function (h) { return { text: h, style: 'th' }; });

            var body = [head];
            s.stops.forEach(function (r) {
                body.push([
                    String(r.sr), String(r.invoiceNo || ''), String(r.accountName || ''), String(r.area || ''),
                    { text: money(r.invoiceTotal), alignment: 'right' },
                    { text: money(r.previousBalance), alignment: 'right' },
                    { text: money(r.totalDue), alignment: 'right', bold: true },
                    '', '', ''          // the salesman's three columns, blank for the pen
                ]);
            });
            body.push([
                { text: tr('ui.stops', 'Stops') + ': ' + s.stopCount, colSpan: 4, style: 'th', alignment: 'right' },
                '', '', '',
                { text: money(s.invoiceTotal), style: 'th', alignment: 'right' },
                { text: '', style: 'th' },
                { text: money(s.totalDue), style: 'th', alignment: 'right' },
                { text: '', style: 'th' }, { text: '', style: 'th' }, { text: '', style: 'th' }
            ]);

            global.pdfMake.createPdf({
                pageOrientation: 'landscape',
                pageMargins: [18, 18, 18, 24],
                content: [
                    { text: tr('ui.roundSheet', 'Delivery & Recovery Sheet'), style: 'h' },
                    {
                        text: (s.salesman ? tr('ui.salesman', 'Salesman') + ': ' + s.salesman + '   ·   ' : '')
                            + s.from + ' — ' + s.to,
                        style: 'sub'
                    },
                    { table: { headerRows: 1, widths: [14, 60, '*', 70, 52, 52, 55, 50, 50, 70], body: body }, fontSize: 8 },
                    {
                        // The signature strip. Same reason as the printed version: this is what turns the
                        // sheet from a listing into the only control a one-person back office has left.
                        margin: [0, 18, 0, 0], fontSize: 8, columns: [
                            { text: tr('ui.received', 'Total received') + ': ______________' },
                            { text: tr('ui.salesmanSignature', 'Salesman') + ': ______________' },
                            { text: tr('ui.cashierSignature', 'Cashier') + ': ______________' }
                        ]
                    }
                ],
                styles: {
                    h: { fontSize: 13, bold: true, alignment: 'center' },
                    sub: { fontSize: 9, alignment: 'center', margin: [0, 2, 0, 8] },
                    th: { bold: true, fillColor: '#eeeeee', fontSize: 8 }
                }
            }).download('round-sheet-' + s.from + (s.to !== s.from ? '_' + s.to : '') + '.pdf');
            $('#rsPdfBtn').prop('disabled', false);
        }).catch(function () {
            $('#rsPdfBtn').prop('disabled', false);
            global.showFormError(tr('ui.js.pdfUnavailable', 'PDF export is not available.'));
        });
    };

    // ── slice 5 · the sheet comes back ─────────────────────────────────────────────────────────────────────

    /**
     * Turn the sheet from something you print into something you type into.
     *
     * <p>The same rows either way, on purpose. An operator working from a marked-up page should be reading one
     * line and typing one line, in the same order, with nothing to match up — a separate keying screen with its
     * own ordering is how a payment ends up against the wrong shop.
     */
    global.toggleRoundKeying = function () {
        state.keying = $('#rsKeyMode').is(':checked');
        $('#rsKeyFields').toggle(state.keying);
        $('#rsKeyResult').hide().empty();
        render();
        recalcRoundDeclared();
    };

    /**
     * The running total of what has been typed.
     *
     * <p>Shown NEXT TO the counted box rather than filled into it. They are different facts — one is what the
     * shops said they paid, the other is what is actually in the bag — and pre-filling the count from the
     * declarations would quietly guarantee a zero variance, which is the one number a cash-up exists to reveal.
     */
    global.recalcRoundDeclared = function () {
        var total = 0;
        $('.rs-received').each(function () {
            var v = Number($(this).val());
            if (!isNaN(v) && v > 0) { total += v; }
        });
        $('#rsDeclared').text(state.keying
            ? tr('ui.declared', 'Declared') + ': ' + total.toFixed(2)
            : '');
    };

    /**
     * Key the whole round: every stop, one request.
     *
     * <p>Stops left blank are sent as ZERO, not omitted. A blank line on the sheet means the shop paid nothing —
     * "CR" in the salesman's hand — and that is a fact about the round, not an absence of one. Dropping those
     * rows would settle a different set of stops than the sheet lists, and nobody could then tell a shop that
     * paid nothing from one that was never visited.
     */
    global.keyRound = function () {
        if (!state.sheet || !(state.sheet.stops || []).length) {
            return global.showFormError(tr('ui.js.loadTheSheetFirst', 'Load the sheet first.'));
        }

        var stops = [];
        $('.rs-received').each(function () {
            var orderId = Number($(this).data('order'));
            if (!orderId) { return; }
            var v = Number($(this).val());
            stops.push({ orderId: orderId, amountCollected: (isNaN(v) || v < 0) ? 0 : v });
        });
        if (!stops.length) {
            return global.showFormError(tr('ui.js.nothingToKey', 'There are no stops to key.'));
        }

        var counted = Number($('#rsCounted').val());
        var body = {
            salesman: $.trim($('#rsSalesman').val() || ''),
            countedAmount: isNaN(counted) ? 0 : counted,
            depositReference: $.trim($('#rsDeposit').val() || ''),
            note: $.trim($('#rsNote').val() || ''),
            stops: stops
        };

        $('#rsKeyBtn').prop('disabled', true);
        $.ajax({
            type: 'POST', url: serverContext + 'keyRound', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify(body),
            success: function (resp) {
                $('#rsKeyBtn').prop('disabled', false);
                if (!resp || resp.success === false) {
                    // The server's own words: a variance with no explanation, a period lock, a stale list. Each
                    // of those tells the operator what to do differently, which a generic message does not.
                    return global.showFormError((resp && resp.message)
                        || tr('ui.js.couldNotKeyRound', 'Could not key the round.'));
                }
                showKeyResult(resp.data || {});
                global.loadRoundSheet();          // balances have moved; redraw from the books
            },
            error: function (xhr) {
                $('#rsKeyBtn').prop('disabled', false);
                global.showFormError((xhr.responseJSON && xhr.responseJSON.message)
                    || tr('ui.js.couldNotKeyRound', 'Could not key the round.'));
            }
        });
    };

    /**
     * What happened, stop by stop.
     *
     * <p>The skipped list is rendered as prominently as the success count, deliberately. A round where 27 of 29
     * stops were keyed is a SUCCESS with two things to look at, and burying those two behind a green tick is how
     * a shop quietly never gets its payment recorded.
     */
    function showKeyResult(res) {
        var skipped = res.skipped || [];
        var html = '<div class="alert ' + (skipped.length ? 'alert-warning' : 'alert-success') + '">'
            + '<b>' + esc(tr('ui.keyed', 'Keyed') + ': ' + (res.keyed || 0)) + '</b>'
            + (res.settlementNo
                ? ' &nbsp;·&nbsp; ' + esc(tr('ui.settlement', 'Settlement') + ': ' + res.settlementNo)
                : '')
            + (res.declared != null
                ? ' &nbsp;·&nbsp; ' + esc(tr('ui.declared', 'Declared') + ': ' + money(res.declared))
                : '');
        if (skipped.length) {
            html += '<div style="margin-top:8px"><b>' + esc(tr('ui.notKeyed', 'Not keyed')) + ':</b><ul>'
                + skipped.map(function (m) { return '<li>' + esc(m) + '</li>'; }).join('')
                + '</ul></div>';
        }
        html += '</div>';
        $('#rsKeyResult').html(html).show();
    }

})(window, jQuery);
