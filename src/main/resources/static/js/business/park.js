/*
 * Park / hold & resume a sale (POS R10, slice 40). Park stores the current cart (customer + lines + chosen tender)
 * server-side to resume later — no stock/invoice until completed. Resume rebuilds the cart and removes the held
 * record; completing the sale then goes through the normal addSell. Reuses the global cart array `data` and the
 * checkout fields the sell form already owns.
 */
(function (global) {
    'use strict';

    function buildCartPayload() {
        var payMethod = $('#sellPayMethod').val() || 'CASH';
        var received = $('#sellRec').val() * 1 || 0;
        var customer = {
            name: $('#sellCN').val(), contact: $('#sellCC').val(),
            paidAmount: $('#sellRec').val(), dueAmount: $('#sellCh').val(), dueDate: $('#dueDate').val()
        };
        var tenders = [];
        if (received > 0 || payMethod === 'CREDIT') tenders.push({ method: payMethod, amount: received, reference: '' });
        var cart = { customer: customer, sales: data, tenders: tenders };
        // TRADE-DISC-1: the trade discount is part of the basket. It was never stored, so a resumed sale lost it
        // — and because the field was not cleared either, the NEXT customer got it instead.
        var td = Number($('#sellTradeDiscount').val()) || 0;
        if (td > 0) cart.tradeDiscount = td;
        return cart;
    }

    global.parkCurrentSale = function () {
        if (!data || data.length === 0) { showFormError(t('ui.js.cartIsEmptyNothingToPark')); return; }
        var cart = buildCartPayload();
        var label = (cart.customer.name && cart.customer.name.trim())
            ? cart.customer.name.trim() : ('Parked ' + new Date().toLocaleTimeString());
        // The list shows what the customer will PAY (after line and trade discounts) when business.js is present.
        var total = 0;
        if (typeof sellPayable === 'function') total = sellPayable();
        else data.forEach(function (d) { total += Number(d.totalAmount) || 0; });

        $.ajax({
            type: 'POST', url: serverContext + 'parkSale', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ label: label, itemCount: data.length, total: total, cart: cart }),
            success: function (resp) {
                if (resp && resp.status === 'SUCCESS') {
                    showSaleSuccess(t('ui.js.saleParked'));
                    if (typeof resetCart === 'function') resetCart();
                    else { data.length = 0; if (typeof tablesi !== 'undefined' && tablesi) tablesi.clear().draw(); }
                } else { showFormError(apiMessage(resp, 'Could not park the sale.')); }
            },
            error: function () { showFormError(t('ui.js.couldNotParkTheSale')); }
        });
    };

    global.showParked = function () {
        $('.formDiv').hide();
        $('#ParkedDiv').show();
        loadParkedSales();
    };

    function loadParkedSales() {
        $.get(serverContext + 'parkedSales', function (resp) {
            // A List comes back in GenericResponse.collection (not .object) — same convention as getUserSell.
            var list = (resp && resp.collection) ? resp.collection : [];
            var $b = $('#parkedBody').empty();
            $('#parkedEmpty').toggle(list.length === 0);
            list.forEach(function (p) {
                var at = String(p.parkedAt || '').replace('T', ' ').substring(0, 16);
                var tr = $('<tr>');
                tr.append($('<td>').text(p.label || ''));
                tr.append($('<td>').text(p.itemCount != null ? p.itemCount : ''));
                tr.append($('<td>').text(p.total != null ? Number(p.total).toFixed(2) : ''));
                tr.append($('<td>').text(at));
                tr.append($('<td>').html(
                    "<button class='btn btn-xs btn-success' onclick='resumeParked(" + p.id + ")'>Resume</button> "
                    + "<button class='btn btn-xs btn-danger' onclick='discardParked(" + p.id + ")'>Discard</button>"));
                $b.append(tr);
            });
        }).fail(function () { showFormError(t('ui.js.couldNotLoadParkedSales')); });
    }
    global.loadParkedSales = loadParkedSales;

    /*
     * PARK-CLAIM-1 — resume TAKES the parked sale: one server call reads and removes it.
     *
     * This used to GET the cart and then call discardParked(id, true) to remove it. /deleteParked needs
     * DELETE_PRIVILEGE, which a USER-role cashier does not hold, and `silent` swallowed the refusal — so for an
     * ordinary cashier the parked sale stayed in the list after every resume, could be resumed and completed
     * again, and became a second invoice for the same goods. /claimParked removes it in the same transaction
     * that returns it, and a second claim of the same id is told it is gone.
     */
    global.resumeParked = function (id) {
        $.post(serverContext + 'claimParked', { id: id }, function (resp) {
            if (resp && resp.status === 'SUCCESS' && resp.object) {
                $('#sellType').val('sellDiv').trigger('change');   // open the New Sale section
                rebuildCartFromResumed(resp.object);
                showSaleSuccess(t('ui.js.parkedSaleResumed'));
            } else {
                showFormError(apiMessage(resp, 'Could not resume the parked sale.'));
                if (resp && resp.status === 'NOT_FOUND') loadParkedSales();   // it is gone — drop the stale row
            }
        }, 'json').fail(function () { showFormError(t('ui.js.couldNotResumeTheParkedSale')); });
    };

    global.discardParked = function (id, silent) {
        $.post(serverContext + 'deleteParked', { id: id }, function (resp) {
            if (resp && resp.status === 'SUCCESS') { if (!silent) { showSaleSuccess(t('ui.js.parkedSaleDiscarded')); loadParkedSales(); } }
            else if (!silent) { showFormError(apiMessage(resp, 'Could not discard.')); }
        }, 'json').fail(function () { if (!silent) showFormError(t('ui.js.couldNotDiscardTheParkedSale')); });
    };

    /*
     * CART-1 — the resumed basket goes into data[] and the grid is DRAWN from it by business.js's renderCart():
     * the same row builder as every other add path. This used to hand-build rows that printed the raw
     * quantity ("0.25" for ten tablets — the U13 defect) and needed its own copy of the discount rule.
     */
    function rebuildCartFromResumed(cart) {
        data.length = 0;
        (cart.sales || []).forEach(function (line) { data.push(line); });
        var c = cart.customer || {};
        if (typeof onCustomerModeChange === 'function') onCustomerModeChange('manual');
        $('#sellCN').val(c.name || '');
        $('#sellCC').val(c.contact || '');
        $('#sellRec').val('');
        $('#sellCh,#sellDueThis').val('');
        // TRADE-DISC-1: the basket's trade discount comes back with it (absent on rows parked before this = none).
        // Set BEFORE the render, so the Change/Due it computes already include it.
        $('#sellTradeDiscount').val(cart.tradeDiscount != null && Number(cart.tradeDiscount) > 0
            ? Number(cart.tradeDiscount).toFixed(2) : '');
        if (typeof renderCart === 'function') renderCart();
    }
    global.rebuildCartFromResumed = rebuildCartFromResumed;
})(window);
