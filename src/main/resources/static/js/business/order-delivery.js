/**
 * OMS O7 D4 — the delivery-return keying screen. Javed's screen, not Ahsan's.
 *
 * Ahsan has no device (§6 D-5). He carries the printed invoices, the shop signs them, and Javed keys the
 * outcome per parcel when he gets back. That decision shapes every label here:
 *
 *   * the screen says **Recorded**, never *Delivered at* — the timestamp is when this was typed, which may be
 *     hours after the goods arrived, and the system must not state a time it did not observe;
 *   * the driver is captured as a NOTE, not as the person confirming — attributing a keyed entry to whoever
 *     was at the door would record an observation as coming from someone who did not make it;
 *   * quantities default to "all of it arrived" but every one is editable, because a shop taking 8 of 10 or
 *     refusing a short-expiry batch is routine in this trade, and anything short is credited back.
 *
 * Delivery and payment are kept as SEPARATE questions. Most of this trade is on credit, so goods arriving and
 * money arriving are different events and neither is inferred from the other.
 */
(function (global) {
    'use strict';

    var state = { orderId: null, shipment: null, lines: [] };

    function esc(s) {
        return (global.escHtml ? global.escHtml(String(s == null ? '' : s)) : String(s == null ? '' : s));
    }

    function tr(key, fallback) {
        var v = (typeof global.t === 'function') ? global.t(key) : null;
        return (v && v !== key) ? v : fallback;
    }

    /** Open the keying panel for an order's most recent undelivered parcel. */
    global.openDeliveryForm = function (orderId) {
        state.orderId = orderId;
        $.get(serverContext + 'getOrder?id=' + encodeURIComponent(orderId), function (resp) {
            var o = (resp && resp.data) || {};
            var parcels = (o.shipments || []).filter(function (s) { return s.status === 'DISPATCHED'; });
            if (!parcels.length) {
                return global.uiAlert(tr('ui.js.noParcelToRecord',
                    'There is no dispatched parcel on this order left to record.'));
            }
            // Oldest undelivered first: a van comes back in the order it went out, and keying the newest
            // first is how a parcel gets skipped.
            state.shipment = parcels[0];
            state.lines = (state.shipment.lines || []).map(function (l) {
                return {
                    orderItemId: l.orderItemId,
                    name: l.productName || ('#' + l.orderItemId),
                    sent: Number(l.quantity) || 0,
                    delivered: Number(l.quantity) || 0     // default: it all arrived, which is the common case
                };
            });
            $('#delTitle').text((o.orderNo || ('#' + orderId)) + ' — ' + esc(state.shipment.shipmentNo || ''));
            $('#delInvoice').text(state.shipment.invoiceNo || tr('ui.js.noInvoice', '(no invoice)'));
            $('#delCarrier').val(state.shipment.carrier || '');
            $('#delSettlement').val('CREDIT');
            $('#delAmount').val('');
            $('#delNote').val('');
            renderLines();
            $('.formDiv').hide();
            $('#DeliveryDiv').show();
        });
    };

    function renderLines() {
        var html = '';
        state.lines.forEach(function (l) {
            var back = l.sent - l.delivered;
            html += '<tr data-row="' + Number(l.orderItemId) + '">'
                + '<td>' + esc(l.name) + '</td>'
                + '<td class="text-right">' + esc(l.sent) + '</td>'
                + '<td class="text-right" style="width:120px">'
                + '<input type="number" class="form-control input-sm del-qty" data-line="' + Number(l.orderItemId)
                + '" min="0" max="' + esc(l.sent) + '" value="' + esc(l.delivered) + '" inputmode="numeric"></td>'
                + '<td class="text-right del-back">' + (back > 0
                    ? '<span class="label label-warning">' + esc(back) + '</span>' : '0') + '</td>'
                + '</tr>';
        });
        $('#delBody').html(html);
        refreshSummary();
    }

    /**
     * Updates the totals WITHOUT rebuilding the rows.
     *
     * D3 learned this the hard way: re-rendering the table on every keystroke destroys the field being typed
     * into, so "12" becomes "1". The inputs are never touched here.
     */
    function refreshSummary() {
        var sent = 0, taken = 0;
        state.lines.forEach(function (l) {
            sent += l.sent;
            taken += l.delivered;
            var back = l.sent - l.delivered;
            $('#delBody tr[data-row="' + Number(l.orderItemId) + '"] .del-back').html(
                back > 0 ? '<span class="label label-warning">' + esc(back) + '</span>' : '0');
        });
        var outcome = taken === 0 ? 'REFUSED' : (taken < sent ? 'PARTIAL' : 'DELIVERED');
        $('#delOutcome').text(tr('ui.js.outcome' + outcome, outcome));
        // Say plainly what will happen to the money — a credit note is a real document, not a side effect.
        $('#delCreditWarn').toggle(taken < sent).text(tr('ui.js.willCreditBack',
            'A credit note will be raised for what came back.'));
    }

    $(document).on('input', '.del-qty', function () {
        var id = Number($(this).data('line'));
        var l = state.lines.filter(function (x) { return String(x.orderItemId) === String(id); })[0];
        if (!l) { return; }
        l.delivered = Math.max(0, Math.min(Number($(this).val()) || 0, l.sent));
        refreshSummary();
    });

    global.submitDelivery = function () {
        if (!state.shipment) { return; }
        $('#delSubmit').prop('disabled', true);
        $.ajax({
            type: 'POST', url: serverContext + 'recordDelivery', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({
                id: state.orderId,
                shipmentId: state.shipment.id,
                deliveredBy: $.trim($('#delCarrier').val() || ''),
                settlement: $('#delSettlement').val(),
                amountCollected: Number($('#delAmount').val() || 0),
                note: $.trim($('#delNote').val() || ''),
                lines: state.lines.map(function (l) {
                    return { orderItemId: l.orderItemId, deliveredQuantity: l.delivered };
                })
            }),
            success: function (resp) {
                if (resp && resp.success) {
                    var cns = (resp.data && resp.data.creditNotes) || [];
                    global.showSaleSuccess(tr('ui.js.deliveryRecorded', 'Delivery recorded')
                        + (cns.length ? ' — ' + tr('ui.js.creditNote', 'credit note') + ' ' + cns.join(', ') : ''));
                    if (typeof global.showOrders === 'function') { global.showOrders(); }
                } else {
                    global.showFormError((resp && resp.message)
                        || tr('ui.js.couldNotRecordDelivery', 'Could not record the delivery.'));
                    $('#delSubmit').prop('disabled', false);
                }
            },
            error: function (xhr) {
                global.showFormError((xhr.responseJSON && xhr.responseJSON.message)
                    || tr('ui.js.couldNotRecordDelivery', 'Could not record the delivery.'));
                $('#delSubmit').prop('disabled', false);
            }
        });
    };

    global.closeDeliveryForm = function () {
        if (typeof global.showOrders === 'function') { global.showOrders(); }
    };
})(window);
