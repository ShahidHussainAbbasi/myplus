/*
 * E-commerce orders back-office (slice 46) — ECOMMERCE-only, on the single shared dashboard. An order is a normal
 * trade sale (reused Sell saga) plus a fulfilment lifecycle. A Store user's completed sale is auto-recorded as an
 * order (main.js post-sale hook → recordOrder); the Orders screen lists them + advances fulfilment status.
 * marketplace-service uses the common-web ApiResponse envelope (reads in resp.data).
 */
(function (global) {
    'use strict';

    var NEXT = { NEW: 'PACKED', PACKED: 'SHIPPED', SHIPPED: 'DELIVERED' };

    global.showOrders = function () {
        $('.formDiv').hide();
        $('#OrdersDiv').show();
        loadOrders();
    };

    function loadOrders() {
        $.get(serverContext + 'getOrders', function (resp) {
            var list = (resp && resp.data) ? resp.data : [];
            var $b = $('#ordersBody').empty();
            $('#ordersEmpty').toggle(list.length === 0);
            list.forEach(function (o) {
                var at = String(o.createdAt || '').replace('T', ' ').substring(0, 16);
                var next = NEXT[o.fulfilmentStatus];
                var action = next
                    ? "<button class='btn btn-xs btn-primary' onclick=\"advanceOrder(" + o.id + ",'" + next + "')\">Mark " + next + "</button>"
                    : '<span class="text-muted">' + (o.fulfilmentStatus || '') + '</span>';
                // Cancel is available until delivered; it returns the order's stock to inventory (slice 51).
                if (o.fulfilmentStatus !== 'CANCELLED' && o.fulfilmentStatus !== 'DELIVERED') {
                    action += " <button class='btn btn-xs btn-danger' onclick=\"cancelOrder(" + o.id + ")\">Cancel</button>";
                }
                var tr = $('<tr>');
                tr.append($('<td>').text(o.invoiceNo || ''));
                tr.append($('<td>').text(o.customerName || ''));
                tr.append($('<td>').text(o.total != null ? Number(o.total).toFixed(2) : ''));
                tr.append($('<td>').text(o.fulfilmentStatus || ''));
                tr.append($('<td>').text(at));
                tr.append($('<td>').html(action));
                $b.append(tr);
            });
        }).fail(function () { showFormError('Could not load orders.'); });
    }
    global.loadOrders = loadOrders;

    global.advanceOrder = function (id, status) {
        $.ajax({
            type: 'POST', url: serverContext + 'updateOrderStatus', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ id: id, status: status }),
            success: function (resp) {
                if (resp && resp.success) { showSaleSuccess('Order → ' + status); loadOrders(); }
                else showFormError((resp && resp.message) || 'Could not update the order.');
            },
            error: function () { showFormError('Could not update the order.'); }
        });
    };

    global.cancelOrder = function (id) {
        if (!confirm('Cancel this order and return its stock to inventory?')) return;
        $.ajax({
            type: 'POST', url: serverContext + 'updateOrderStatus', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ id: id, status: 'CANCELLED' }),
            success: function (resp) {
                if (resp && resp.success) { showSaleSuccess('Order cancelled — stock returned.'); loadOrders(); }
                else showFormError((resp && resp.message) || 'Could not cancel the order.');
            },
            error: function () { showFormError('Could not cancel the order.'); }
        });
    };

    // Called by main.js after a successful addSell when the user is a Store (ECOMMERCE) vertical: the sale becomes
    // an order with fulfilment status NEW.
    global.recordOrder = function (invoiceNo) {
        var total = 0;
        (global.data || []).forEach(function (d) { total += Number(d.totalAmount) || 0; });
        $.ajax({
            type: 'POST', url: serverContext + 'recordOrder', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ invoiceNo: invoiceNo, customerName: $('#sellCN').val(), total: total }),
            success: function (resp) { if (resp && resp.success) showSaleSuccess('Order ' + invoiceNo + ' created.'); }
        });
    };
})(window);
