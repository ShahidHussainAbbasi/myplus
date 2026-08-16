/**
 * OMS O7 D5 — driver settlement / remittance. The day-end cash-up.
 * Design: microservices/docs/slices/oms-O7-distribution-presales.md §13
 *
 * Ahsan has no device (§6 D-5), so the signed paper invoices and the cash bag are the ONLY controls on the
 * money. D4 records what he says each shop paid. THIS screen is where that is compared with what is actually
 * in the bag — and pressing Settle is what posts the receipts to AR. That ordering is deliberate: if the money
 * reached the books at keying time, this reconciliation would be a report nobody had to run, which is exactly
 * the gap backlog item B1 describes.
 *
 * Three things the screen must never soften:
 *   * a row here is a CLAIM that somebody is holding the company's money — it is not an invoice, and it stays
 *     until it is counted;
 *   * the variance is stated in words as well as a number ("300.00 short"), because a minus sign in a column
 *     is the easiest thing in the world to skim past;
 *   * "Recorded" never "Delivered at" — D4's timestamp is when the admin typed it, not when the goods arrived.
 */
(function (global) {
    'use strict';

    var state = { rows: [], driver: '' };

    function esc(s) {
        return (global.escHtml ? global.escHtml(String(s == null ? '' : s)) : String(s == null ? '' : s));
    }

    function tr(key, fallback) {
        var v = (typeof global.t === 'function') ? global.t(key) : null;
        return (v && v !== key) ? v : fallback;
    }

    function money(v) { var n = Number(v); return isNaN(n) ? '0.00' : n.toFixed(2); }

    /**
     * yyyy-MM-dd from LOCAL components.
     *
     * NOT toISOString(): that converts to UTC, so at 02:00 in Karachi it reports YESTERDAY — and a settlement
     * dated a day early lands in the wrong accounting period, or in a closed one. This has already broken one
     * gate in this programme.
     */
    function localIsoDate(d) {
        var p = function (n) { return (n < 10 ? '0' : '') + n; };
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
    }

    /** dd-MM-yyyy — the wire format the shared date picker writes, and what its visible box must start as. */
    function localDisplayDate(d) {
        var p = function (n) { return (n < 10 ? '0' : '') + n; };
        return p(d.getDate()) + '-' + p(d.getMonth() + 1) + '-' + d.getFullYear();
    }

    global.showDriverSettlement = function () {
        $('.formDiv').hide();
        $('#DriverSettlementDiv').show();
        var today = new Date();
        $('#dsDate').val(localIsoDate(today));
        $('#dsDateShown').val(localDisplayDate(today));
        $('#dsCounted').val('');
        $('#dsDeposit').val('');
        $('#dsNote').val('');
        loadCollections();
        loadHistory();
    };

    function loadCollections() {
        $.get(serverContext + 'getDriverCollections?size=200', function (resp) {
            if (!resp || resp.success === false) {
                return global.showFormError((resp && resp.message)
                    || tr('ui.js.couldNotLoadCollections', 'Could not load the collections.'));
            }
            var page = resp.data || {};
            state.rows = (page.content || []).map(function (c) {
                return {
                    id: c.id,
                    recordedAt: c.recordedAt,
                    customerName: c.customerName,
                    invoiceNo: c.invoiceNo,
                    driver: c.deliveredBy || '',
                    amount: Number(c.amountCollected) || 0,
                    checked: true            // everything the chosen driver owes is being handed over — that
                                             // is the normal case, and unticking is the exception
                };
            });
            buildDriverFilter();
            render();
        }).fail(function () {
            global.showFormError(tr('ui.js.couldNotLoadCollections', 'Could not load the collections.'));
        });
    }

    /**
     * The driver list is built from the data, not from a master — D4 records the driver as free text ("a note,
     * not an identity"), so these ARE the drivers as far as the system knows. Defaulting to the first one keeps
     * the common path on a single driver, which is the only shape the server will settle.
     */
    function buildDriverFilter() {
        var seen = [], html = '<option value="">' + esc(tr('ui.allDrivers', 'All drivers')) + '</option>';
        state.rows.forEach(function (r) { if (seen.indexOf(r.driver) < 0) seen.push(r.driver); });
        seen.forEach(function (d) {
            html += '<option value="' + esc(d) + '">' + esc(d || '(unnamed)') + '</option>';
        });
        if (!state.driver || seen.indexOf(state.driver) < 0) state.driver = seen.length ? seen[0] : '';
        $('#dsDriver').html(html).val(state.driver);
        if ($.fn.selectpicker) { $('#dsDriver').selectpicker('refresh'); }
    }

    function visibleRows() {
        return state.rows.filter(function (r) { return !state.driver || r.driver === state.driver; });
    }

    function render() {
        var rows = visibleRows(), html = '';
        rows.forEach(function (r) {
            html += '<tr data-row="' + Number(r.id) + '">'
                + '<td style="width:36px"><input type="checkbox" class="ds-pick" data-id="' + Number(r.id) + '"'
                + (r.checked ? ' checked' : '') + '></td>'
                + '<td>' + esc(String(r.recordedAt || '').replace('T', ' ').substring(0, 16)) + '</td>'
                + '<td>' + esc(r.customerName || '—') + '</td>'
                + '<td>' + esc(r.invoiceNo || '—') + '</td>'
                + '<td>' + esc(r.driver || '—') + '</td>'
                + '<td class="text-right">' + esc(money(r.amount)) + '</td>'
                + '</tr>';
        });
        $('#dsBody').html(html || '<tr><td colspan="6" class="text-muted">'
            + esc(tr('ui.js.noOpenCollections', 'No cash is waiting to be handed over.')) + '</td></tr>');
        refreshTotals();
    }

    /**
     * Totals only — the rows are never rebuilt here.
     *
     * D3 learned this the hard way: re-rendering a table on every keystroke destroys the field being typed
     * into, so "1200" becomes "1". The counted box is an input on this screen too.
     */
    function refreshTotals() {
        var declared = 0, n = 0;
        visibleRows().forEach(function (r) { if (r.checked) { declared += r.amount; n++; } });
        var counted = Number($('#dsCounted').val() || 0);
        var variance = counted - declared;

        $('#dsDeclared').text(money(declared));
        $('#dsCount').text(n);

        var word, colour;
        if (!$('#dsCounted').val()) { word = ''; colour = ''; }
        else if (Math.abs(variance) < 0.005) { word = tr('ui.js.cashBalanced', 'Balanced'); colour = '#0a7d33'; }
        else if (variance < 0) { word = money(Math.abs(variance)) + ' ' + tr('ui.js.cashShort', 'short'); colour = '#c0392b'; }
        else { word = money(variance) + ' ' + tr('ui.js.cashOver', 'over'); colour = '#b8860b'; }

        $('#dsVariance').text(word ? money(variance) + '  (' + word + ')' : '—').css('color', colour);
        // A difference nobody explained is the failure this screen exists to catch, so the requirement is
        // stated the moment it applies rather than sprung as a refusal after Settle is pressed.
        $('#dsVarianceHint').toggle(!!$('#dsCounted').val() && Math.abs(variance) >= 0.005)
            .text(tr('ui.js.explainVariance', 'Explain the difference in the note before settling.'));
        $('#dsSettle').prop('disabled', n === 0);
    }

    $(document).on('change', '#dsDriver', function () {
        state.driver = $(this).val() || '';
        render();
    });

    $(document).on('change', '.ds-pick', function () {
        var id = Number($(this).data('id')), on = $(this).is(':checked');
        state.rows.forEach(function (r) { if (r.id === id) r.checked = on; });
        refreshTotals();
    });

    $(document).on('change', '#dsPickAll', function () {
        var on = $(this).is(':checked');
        visibleRows().forEach(function (r) { r.checked = on; });
        $('#dsBody .ds-pick').prop('checked', on);
        refreshTotals();
    });

    $(document).on('input', '#dsCounted', refreshTotals);

    global.settleDriver = function () {
        var picked = visibleRows().filter(function (r) { return r.checked; });
        if (!picked.length) {
            return global.uiAlert(tr('ui.js.chooseCollections',
                'Choose the collections this driver is handing over.'));
        }
        // uiConfirm resolves a boolean — never window.confirm, per the platform's dialog contract.
        global.uiConfirm({
            title: tr('ui.settleDriver', 'Settle driver'),
            message: tr('ui.js.confirmSettleDriver',
                'Post these receipts and close the cash for this driver today?'),
            confirmText: tr('ui.settleDriver', 'Settle driver'),
            tone: 'warning'
        }).then(function (ok) {
            if (!ok) { return; }
            $('#dsSettle').prop('disabled', true);
            $.ajax({
                type: 'POST', url: serverContext + 'settleDriver', contentType: 'application/json', dataType: 'json',
                data: JSON.stringify({
                    deliveryIds: picked.map(function (r) { return r.id; }),
                    countedAmount: Number($('#dsCounted').val() || 0),
                    settlementDate: $('#dsDate').val() || localIsoDate(new Date()),
                    depositReference: $.trim($('#dsDeposit').val() || ''),
                    note: $.trim($('#dsNote').val() || '')
                }),
                success: function (resp) {
                    if (resp && resp.success) {
                        var d = resp.data || {};
                        var receipts = d.receipts || [];
                        global.showSaleSuccess(tr('ui.js.driverSettled', 'Driver settled') + ' — '
                            + esc(d.settlementNo || '')
                            + (receipts.length ? ' · ' + tr('ui.receipts', 'Receipts') + ' ' + receipts.join(', ') : ''));
                        global.showDriverSettlement();
                    } else {
                        // Relayed verbatim: every refusal here is something the admin must ACT on — a short bag
                        // with no explanation, two drivers in one bag, a closed period — and none is fixed by
                        // pressing the button again.
                        global.showFormError((resp && resp.message)
                            || tr('ui.js.couldNotSettleDriver', 'Could not settle the driver.'));
                        $('#dsSettle').prop('disabled', false);
                    }
                },
                error: function (xhr) {
                    global.showFormError((xhr.responseJSON && xhr.responseJSON.message)
                        || tr('ui.js.couldNotSettleDriver', 'Could not settle the driver.'));
                    $('#dsSettle').prop('disabled', false);
                }
            });
        });
    };

    function loadHistory() {
        $.get(serverContext + 'getDriverSettlements?size=15', function (resp) {
            var page = (resp && resp.data) || {};
            var html = '';
            (page.content || []).forEach(function (s) {
                var v = Number(s.varianceAmount) || 0;
                var colour = Math.abs(v) < 0.005 ? '#0a7d33' : (v < 0 ? '#c0392b' : '#b8860b');
                html += '<tr>'
                    + '<td>' + esc(s.settlementNo || '') + '</td>'
                    + '<td>' + esc(s.settlementDate || '') + '</td>'
                    + '<td>' + esc(s.driverName || '—') + '</td>'
                    + '<td class="text-right">' + esc(s.collectionCount || 0) + '</td>'
                    + '<td class="text-right">' + esc(money(s.declaredAmount)) + '</td>'
                    + '<td class="text-right">' + esc(money(s.countedAmount)) + '</td>'
                    + '<td class="text-right" style="color:' + colour + '"><b>' + esc(money(v)) + '</b></td>'
                    + '<td>' + esc(s.settledByName || '') + '</td>'
                    + '</tr>';
            });
            $('#dsHistBody').html(html || '<tr><td colspan="8" class="text-muted">—</td></tr>');
        });
    }

    global.refreshDriverCollections = function () { loadCollections(); loadHistory(); };
})(window);
