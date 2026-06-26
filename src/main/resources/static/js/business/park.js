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
        return { customer: customer, sales: data, tenders: tenders };
    }

    global.parkCurrentSale = function () {
        if (!data || data.length === 0) { showFormError('Cart is empty — nothing to park.'); return; }
        var cart = buildCartPayload();
        var label = (cart.customer.name && cart.customer.name.trim())
            ? cart.customer.name.trim() : ('Parked ' + new Date().toLocaleTimeString());
        var total = 0;
        data.forEach(function (d) { total += Number(d.totalAmount) || 0; });

        $.ajax({
            type: 'POST', url: serverContext + 'parkSale', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ label: label, itemCount: data.length, total: total, cart: cart }),
            success: function (resp) {
                if (resp && resp.status === 'SUCCESS') {
                    showSaleSuccess('Sale parked.');
                    if (typeof resetCart === 'function') resetCart();
                    else { data.length = 0; if (typeof tablesi !== 'undefined' && tablesi) tablesi.clear().draw(); }
                } else { showFormError((resp && resp.message) || 'Could not park the sale.'); }
            },
            error: function () { showFormError('Could not park the sale.'); }
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
        }).fail(function () { showFormError('Could not load parked sales.'); });
    }
    global.loadParkedSales = loadParkedSales;

    global.resumeParked = function (id) {
        $.get(serverContext + 'resumeParked?id=' + encodeURIComponent(id), function (resp) {
            if (resp && resp.status === 'SUCCESS' && resp.object) {
                $('#sellType').val('sellDiv').trigger('change');   // open the New Sale section
                rebuildCartFromResumed(resp.object);
                discardParked(id, true);                            // it's back in the cart now
                showSaleSuccess('Parked sale resumed.');
            } else { showFormError((resp && resp.message) || 'Could not resume the parked sale.'); }
        }).fail(function () { showFormError('Could not resume the parked sale.'); });
    };

    global.discardParked = function (id, silent) {
        $.post(serverContext + 'deleteParked', { id: id }, function (resp) {
            if (resp && resp.status === 'SUCCESS') { if (!silent) { showSaleSuccess('Parked sale discarded.'); loadParkedSales(); } }
            else if (!silent) { showFormError((resp && resp.message) || 'Could not discard.'); }
        }, 'json').fail(function () { if (!silent) showFormError('Could not discard the parked sale.'); });
    };

    function rebuildCartFromResumed(cart) {
        data.length = 0;
        if (typeof tablesi !== 'undefined' && tablesi) tablesi.clear();
        (cart.sales || []).forEach(function (line) {
            data.push(line);
            var stk = line.stock || {};
            if (typeof tablesi !== 'undefined' && tablesi) {
                tablesi.row.add([
                    line.itemId, escHtml(line.itemName || ''), line.quantity,
                    (line.sellRate != null ? line.sellRate : (stk.bsellRate != null ? stk.bsellRate : '')),
                    (line.discount != null ? line.discount : (stk.bsellDiscount != null ? stk.bsellDiscount : '')),
                    (line.totalAmount != null ? line.totalAmount : ''),
                    "<button id='DII' onclick=UIT(" + line.itemId + ")>Del</button>"
                ]);
            }
        });
        if (typeof tablesi !== 'undefined' && tablesi) tablesi.draw();
        var c = cart.customer || {};
        if (typeof onCustomerModeChange === 'function') onCustomerModeChange('manual');
        $('#sellCN').val(c.name || '');
        $('#sellCC').val(c.contact || '');
        $('#sellRec').val('');
        $('#sellCh,#sellDueThis').val('');
    }
    global.rebuildCartFromResumed = rebuildCartFromResumed;
})(window);
