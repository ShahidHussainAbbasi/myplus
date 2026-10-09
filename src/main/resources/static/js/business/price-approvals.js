/**
 * PR-4 — Purchase → Price approvals: prices purchases would set, waiting for an owner or admin.
 *
 * Design: microservices/docs/selling-price-per-purchase-analysis.md §11. The server is the judge of everything here —
 * who may decide (owner/admin, enforced in catalog), which tenant's rows exist, and whether the price moved since the
 * proposal (Approve sends the price this screen showed; a price that moved is refused, never overwritten).
 *
 *   showPriceApprovals()          open the screen on "Waiting"
 *   refreshPriceApprovalCount()   the red count beside the menu item; hidden at zero and for anyone not allowed
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
    function money(v) { return v == null || v === '' ? '—' : (Math.round(Number(v) * 100) / 100).toFixed(2); }
    function when(iso) {
        if (!iso) return '';
        var d = new Date(iso);   // sent with its offset, so this is the viewer's own time
        return isNaN(d.getTime()) ? iso : d.toLocaleString();
    }
    function post(path, data) {
        // global:false keeps the page-wide veil off a one-row decision; it also skips the CSRF hook (L16), so pass it.
        return $.ajax({ type: 'POST', url: global.serverContext + path, data: data, dataType: 'json', global: false,
            headers: (typeof global.xsrfHeaders === 'function') ? global.xsrfHeaders() : {} });
    }

    var status = 'PENDING';

    function why(r) {
        if (r.reason === 'NEVER_LOWER') return t('ui.js.paWhyLower', 'Auto never lowers a price') + (r.detail ? ' · ' + r.detail : '');
        if (r.reason === 'MAX_RISE') return t('ui.js.paWhyRise', 'Above the Auto rise limit') + (r.detail ? ' · ' + r.detail : '');
        return r.detail || (r.source === 'PURCHASE' ? t('ui.js.paWhyBill', "the bill's sell rate") : '');
    }

    function statusLabel(s) {
        return { APPROVED: t('ui.js.paStApproved', 'Approved'), REJECTED: t('ui.js.paStRejected', 'Rejected'),
            SUPERSEDED: t('ui.js.paStSuperseded', 'Replaced by a newer purchase'), PENDING: t('ui.js.paStPending', 'Waiting') }[s] || s;
    }

    function row(r) {
        var pct = r.changePct == null ? null : Number(r.changePct);
        var $change = $('<td style="text-align:right;font-variant-numeric:tabular-nums">')
            .text(pct == null ? '—' : (pct > 0 ? '+' : '') + pct.toFixed(1) + '%')
            .css('color', pct != null && pct < 0 ? '#b42318' : '');
        var $act = $('<td>');
        if (r.status === 'PENDING') {
            $act.append($('<button type="button" class="btn btn-success btn-xs pa-approve">').text(t('ui.js.paApprove', 'Approve')))
                .append(' ')
                .append($('<button type="button" class="btn btn-default btn-xs pa-reject">').text(t('ui.js.paReject', 'Reject')));
        } else {
            $act.append($('<span class="label label-default">').text(statusLabel(r.status)))
                .append(r.decisionNote ? $('<div class="help-block" style="margin:2px 0 0">').text(r.decisionNote) : '');
        }
        return $('<tr>').attr({ 'data-id': r.id, 'data-product': r.productId, 'data-now': r.priceNow })
            .append($('<td>').text(r.productName || ('#' + r.productId)))
            .append($('<td style="text-align:right;font-variant-numeric:tabular-nums">').text(money(r.priceNow)))
            .append($('<td style="text-align:right;font-variant-numeric:tabular-nums;font-weight:600">').text(money(r.proposedPrice)))
            .append($change)
            .append($('<td>').text(why(r)))
            .append($('<td>').text(r.ref || ''))
            .append($('<td>').text(when(r.proposedAt)))
            .append($act);
    }

    function load() {
        var $body = $('#tablePriceApprovals tbody').empty()
            .append($('<tr>').append($('<td colspan="8">').text(t('ui.js.loading', 'Loading…'))));
        $.ajax({ url: global.serverContext + 'priceApprovals', data: status ? { status: status } : {}, dataType: 'json', global: false })
            .done(function (resp) {
                $body.empty();
                var rows = (resp && (resp.data || resp.object)) || [];
                if (resp && resp.success === false) {
                    $body.append($('<tr>').append($('<td colspan="8" class="text-danger">').text(resp.message || t('ui.js.paFailed', 'Could not load the price changes.'))));
                    return;
                }
                if (!rows.length) {
                    $body.append($('<tr>').append($('<td colspan="8" class="text-muted">').text(status === 'PENDING'
                        ? t('ui.js.paNoneWaiting', 'Nothing is waiting for approval.') : t('ui.js.noDataYet', 'No data yet'))));
                    return;
                }
                rows.forEach(function (r) { $body.append(row(r)); });
            })
            .fail(function () {
                $body.empty().append($('<tr>').append($('<td colspan="8" class="text-danger">').text(t('ui.js.paFailed', 'Could not load the price changes.'))));
            });
        refreshCount();
    }

    function showPriceApprovals() {
        $('.formDiv').hide();
        $('#PriceApprovalsDiv').show();
        load();
    }

    function refreshCount() {
        var $b = $('#paCount, #paCountTop');   // inside the Purchase menu, and on its button (seen while it is closed)
        if (!$b.length) return;   // not rendered for this user: they may not approve
        $.ajax({ url: global.serverContext + 'priceApprovalCount', dataType: 'json', global: false })
            .done(function (resp) {
                var n = resp && resp.data ? Number(resp.data.pending) : 0;
                if (n > 0) $b.text(n).attr('aria-label', t('ui.js.paCountLabel', '{0} waiting', [n])).show(); else $b.hide().empty();
            })
            .fail(function () { $b.hide(); });
    }

    // A saved purchase is what proposes a price, so the count must move with it — not only on the next page load.
    // Purchase saves go through callAjax, which stays global (main.js PERF-13) precisely so hooks like this one fire.
    $(document).ajaxComplete(function (e, xhr, settings) {
        if (settings && /(^|\/)(addPurchase|updatePurchase)(\?|$)/.test(String(settings.url || ''))) refreshCount();
    });

    $(document).on('click', '#PriceApprovalsDiv [data-pa-status]', function () {
        status = $(this).attr('data-pa-status');
        $('#PriceApprovalsDiv [data-pa-status]').removeClass('active').attr('aria-pressed', 'false');
        $(this).addClass('active').attr('aria-pressed', 'true');
        load();
    });

    function decided($btn, resp, okText) {
        if (resp && resp.success !== false) {
            if (typeof global.showSaleSuccess === 'function') global.showSaleSuccess(okText);
            load();
        } else {
            $btn.prop('disabled', false);
            var msg = (resp && resp.message) || t('ui.js.paFailed', 'Could not save the decision.');
            if (typeof global.uiAlert === 'function') global.uiAlert({ title: t('ui.js.paNotSaved', 'Not saved'), message: msg, tone: 'danger' });
            else if (typeof global.showFormError === 'function') global.showFormError(msg);
            load();   // the row may have been decided elsewhere, or its price may have moved: show what is true now
        }
    }

    $(document).on('click', '#tablePriceApprovals .pa-approve', function () {
        var $btn = $(this), $tr = $btn.closest('tr');
        var name = $tr.children().eq(0).text(), now = $tr.attr('data-now'), proposed = $tr.children().eq(2).text();
        var ask = typeof global.uiConfirm === 'function'
            ? global.uiConfirm({ title: t('ui.js.paApproveTitle', 'Approve this price?'),
                message: t('ui.js.paApproveMsg', 'Set the selling price of {0} from {1} to {2}. Customers are charged the new price from now on.',
                    [name, money(now), proposed]),
                confirmText: t('ui.js.paApprove', 'Approve'), cancelText: t('ui.js.cancel', 'Cancel') })
            : $.Deferred().resolve(global.confirm(name + ': ' + money(now) + ' → ' + proposed + '?')).promise();
        ask.then(function (yes) {
            if (yes !== true) return;
            $btn.prop('disabled', true);
            post('approvePriceChange', { id: $tr.attr('data-id'), expectedCurrent: now })
                .done(function (resp) {
                    // TP-1: an approved price moves the price every product picker carries; global:false means the
                    // picker's own ajaxComplete hook never sees this post, so the cache is dropped here.
                    if (resp && resp.success !== false && global.ProductPicker) global.ProductPicker.invalidate();
                    decided($btn, resp, t('ui.js.paApprovedMsg', 'Price approved.'));
                })
                .fail(function () { decided($btn, null); });
        });
    });

    $(document).on('click', '#tablePriceApprovals .pa-reject', function () {
        var $btn = $(this), $tr = $btn.closest('tr');
        var ask = typeof global.uiPromptConfirm === 'function'
            ? global.uiPromptConfirm({ title: t('ui.js.paRejectTitle', 'Reject this price?'),
                message: t('ui.js.paRejectMsg', 'The price stays {0}. The decision is kept with the purchase.', [money($tr.attr('data-now'))]),
                input: { label: t('ui.js.paRejectNote', 'Reason (optional)'), maxlength: 255 },
                confirmText: t('ui.js.paReject', 'Reject'), tone: 'danger' })
            : $.Deferred().resolve(global.prompt(t('ui.js.paRejectNote', 'Reason (optional)'), '')).promise();
        ask.then(function (note) {
            if (note === null || note === false) return;
            $btn.prop('disabled', true);
            post('rejectPriceChange', { id: $tr.attr('data-id'), note: note === true ? '' : note })
                .done(function (resp) { decided($btn, resp, t('ui.js.paRejectedMsg', 'Price change rejected.')); })
                .fail(function () { decided($btn, null); });
        });
    });

    global.showPriceApprovals = showPriceApprovals;
    global.refreshPriceApprovalCount = refreshCount;
})(window);
