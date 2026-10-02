/*
 * Cash drawer / till — POS day-close (slice 39). Open a shift with a float, record cash in/out, read an X report
 * (live) or close with a Z report (counted vs expected → variance). Talks to /openShift /currentShift /cashMovement
 * /shiftReport /closeShift. Vertical-aware via the shared dashboard. Money is display-only here; the server is
 * authoritative.
 */
(function (global) {
    'use strict';

    function money(v) { var n = Number(v); return isNaN(n) ? '0.00' : n.toFixed(2); }

    global.showTill = function () {
        $('.formDiv').hide();
        $('#TillDiv').show();
        loadCurrentShift();
    };

    function loadCurrentShift() {
        $('#tillReport').empty();
        $.get(serverContext + 'currentShift', function (resp) {
            if (resp && resp.status === 'SUCCESS' && resp.object) {
                var s = resp.object;
                $('#tillOpenPanel').hide();
                $('#tillOpsPanel').show();
                $('#tillStatus').show().removeClass('alert-warning').addClass('alert-info')
                    .text('Shift OPEN — opened ' + String(s.openedAt || '').replace('T', ' ').substring(0, 16)
                        + ' · float ' + money(s.openingFloat));
            } else {
                $('#tillOpsPanel').hide();
                $('#tillOpenPanel').show();
                $('#tillStatus').show().removeClass('alert-info').addClass('alert-warning').text('No open shift. Open the till to start.');
            }
        }).fail(function () { showFormError(t('ui.js.couldNotLoadTheShift')); });
    }
    global.loadCurrentShift = loadCurrentShift;

    global.openShift = function () {
        $.post(serverContext + 'openShift', { openingFloat: $('#tillFloat').val() || '0' }, function (resp) {
            if (resp && resp.status === 'SUCCESS') { showSaleSuccess(t('ui.js.shiftOpened')); $('#tillFloat').val(''); loadCurrentShift(); }
            else { showFormError(apiMessage(resp, 'Could not open the shift.')); }
        }, 'json').fail(function () { showFormError(t('ui.js.couldNotOpenTheShift')); });
    };

    /**
     * EX-3 — the pay-out category. Shown only for PAY OUT, and only when the tenant has Expense management on
     * (the group carries [data-capability]; capabilities.js marks it cap-off otherwise). Categories load once.
     */
    var tillCategoriesLoaded = false;
    function payOutCategoryWanted() {
        var $g = $('#tillMoveCategoryGroup');
        return $g.length && !$g.hasClass('cap-off') && $('#tillMoveType').val() === 'PAY_OUT';
    }
    function refreshPayOutCategory() {
        var want = payOutCategoryWanted();
        $('#tillMoveCategoryGroup').toggle(!!want);
        if (!want || tillCategoriesLoaded) return;
        $.getJSON(serverContext + 'expense/categories', function (res) {
            var $s = $('#tillMoveCategory').empty().append($('<option>').val('').text(t('ui.js.selectOne')));
            ((res && res.data) || []).filter(function (c) { return c.active; }).forEach(function (c) {
                $s.append($('<option>').val(c.id).text(c.name));
            });
            if (typeof global.refreshSearchableSelect === 'function') global.refreshSearchableSelect($s[0]);
            tillCategoriesLoaded = true;
        });
    }
    $(document).on('change', '#tillMoveType', refreshPayOutCategory);
    $(document).on('capabilities:ready', refreshPayOutCategory);   // capabilities.js fires this once the map is known
    global.refreshPayOutCategory = refreshPayOutCategory;

    /** One key per form fill: a double click or a retry replays the first movement — the cash leaves once (EX-3). */
    var tillMoveKey = null;
    function newTillMoveKey() { return 'till-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10); }

    global.addCashMovement = function (btn) {
        var amt = $('#tillMoveAmount').val();
        if (!amt || Number(amt) <= 0) { showFormError(t('ui.js.enterAnAmountGreaterThan0')); return; }
        if (!tillMoveKey) tillMoveKey = newTillMoveKey();
        var data = { type: $('#tillMoveType').val(), amount: amt, reason: $('#tillMoveReason').val() || '',
                     idempotencyKey: tillMoveKey };
        if (payOutCategoryWanted() && $('#tillMoveCategory').val()) data.categoryId = $('#tillMoveCategory').val();
        var $b = $(btn || '#tillMoveAdd');
        $b.prop('disabled', true);
        $.post(serverContext + 'cashMovement', data,
            function (resp) {
                if (resp && resp.status === 'SUCCESS') {
                    tillMoveKey = null;   // the next fill is a new movement
                    showSaleSuccess(t('ui.js.cashMovementRecorded'));
                    $('#tillMoveAmount').val(''); $('#tillMoveReason').val('');
                    loadShiftReport();
                } else { showFormError(apiMessage(resp, 'Could not record the movement.')); }
            }, 'json')
            .fail(function () { showFormError(t('ui.js.couldNotRecordTheMovement')); })   // key KEPT: a retry replays
            .always(function () { $b.prop('disabled', false); });
    };

    global.loadShiftReport = function () {
        $.get(serverContext + 'shiftReport', function (resp) {
            if (resp && resp.status === 'SUCCESS' && resp.object) renderShiftReport(resp.object, false);
            else showFormError(apiMessage(resp, 'No open shift.'));
        }).fail(function () { showFormError(t('ui.js.couldNotLoadTheReport')); });
    };

    global.closeShift = function () {
        $.post(serverContext + 'closeShift', { countedCash: $('#tillCounted').val() || '0', notes: $('#tillCloseNotes').val() || '' },
            function (resp) {
                if (resp && resp.status === 'SUCCESS' && resp.object) {
                    showSaleSuccess(t('ui.js.shiftClosed'));
                    renderShiftReport(resp.object, true);
                    $('#tillCounted').val(''); $('#tillCloseNotes').val('');
                    loadCurrentShift();
                } else { showFormError(apiMessage(resp, 'Could not close the shift.')); }
            }, 'json').fail(function () { showFormError(t('ui.js.couldNotCloseTheShift')); });
    };

    function renderShiftReport(r, isZ) {
        var rows = '';
        var bm = r.byMethod || {};
        Object.keys(bm).forEach(function (k) {
            rows += '<tr><td>' + escHtml(k) + '</td><td class="text-right">' + money(bm[k]) + '</td></tr>';
        });
        var variance = (r.variance != null) ? Number(r.variance) : null;
        var varColor = variance == null ? '' : (variance === 0 ? '#0a7d33' : (variance < 0 ? '#c0392b' : '#b8860b'));
        var html =
            '<div class="panel panel-default"><div class="panel-heading"><b>' + (isZ ? 'Z REPORT (closed)' : 'X REPORT (live)')
            + '</b> — shift #' + escHtml(r.shiftId) + '</div><div class="panel-body">'
            + '<div class="row"><div class="col-sm-6">'
            + '<table class="table table-condensed">'
            + '<tr><td>Sales count</td><td class="text-right">' + (r.salesCount || 0) + '</td></tr>'
            + '<tr><td>Gross sales</td><td class="text-right">' + money(r.salesGross) + '</td></tr>'
            + '<tr><td>Tax</td><td class="text-right">' + money(r.taxTotal) + '</td></tr>'
            + '<tr><td>Opening float</td><td class="text-right">' + money(r.openingFloat) + '</td></tr>'
            + '<tr><td>Pay-ins</td><td class="text-right">' + money(r.payIns) + '</td></tr>'
            + '<tr><td>Pay-outs</td><td class="text-right">' + money(r.payOuts) + '</td></tr>'
            + '<tr><td>Drops</td><td class="text-right">' + money(r.drops) + '</td></tr>'
            + '<tr><td><b>Expected cash</b></td><td class="text-right"><b>' + money(r.expectedCash) + '</b></td></tr>'
            + (isZ ? '<tr><td>Counted cash</td><td class="text-right">' + money(r.countedCash) + '</td></tr>'
                + '<tr><td><b>Variance</b></td><td class="text-right" style="color:' + varColor + '"><b>' + money(r.variance) + '</b></td></tr>' : '')
            + '</table></div>'
            + '<div class="col-sm-6"><h5>Tenders by method</h5><table class="table table-condensed">'
            + (rows || '<tr><td colspan="2" class="text-muted">No tenders yet</td></tr>') + '</table></div>'
            + '</div></div></div>';
        $('#tillReport').html(html);
    }
    global.renderShiftReport = renderShiftReport;
})(window);
