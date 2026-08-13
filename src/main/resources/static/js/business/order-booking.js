/**
 * OMS O7 D2b — the ORDER BOOKER's screen.
 *
 * The one screen in this application designed to be used standing up, on a phone, in someone else's shop. That
 * is not a styling note — it drives every decision here:
 *
 *   * the outlet's CREDIT STANDING is shown before a single line is entered, because the whole point of moving
 *     that check forward (finding B3) is to save the rep a wasted trip;
 *   * the rep's own recent orders are on the same screen, because "what happened to the order I took here last
 *     week?" is asked at the counter, not back at the depot;
 *   * a rejection shows its REASON inline, since that is the only thing that lets the rep fix it.
 *
 * Its own file rather than a corner of ecommerce.js: that file is the WAREHOUSE's surface (review, ship,
 * refund) and this is the FIELD's. Same domain, different audiences, different screens — and keeping them
 * apart is what stops a booker's screen slowly acquiring the back office's buttons.
 *
 * Everything server-side already exists and is gated (D1 + D2). This adds no endpoint and no rule.
 */
(function (global) {
    'use strict';

    var state = { lines: [], products: [], outlet: null };

    /** XSS: every value below reaches innerHTML, and outlet/product names are user data. */
    function esc(s) {
        return (global.escHtml ? global.escHtml(String(s == null ? '' : s)) : String(s == null ? '' : s));
    }

    function tr(key, fallback) {
        var v = (typeof global.t === 'function') ? global.t(key) : null;
        return (v && v !== key) ? v : fallback;
    }

    function money(v) {
        var n = Number(v || 0);
        return isNaN(n) ? '0.00' : n.toFixed(2);
    }

    global.showOrderBooking = function () {
        $('.formDiv').hide();
        $('#BookingDiv').show();
        state.lines = [];
        state.outlet = null;
        renderLines();
        loadOutlets();
        loadProducts();
        loadMyOrders();
        $('#bkCredit').hide();
    };

    // ── the outlet, and what it owes ────────────────────────────────────────────────────────────────────────

    function loadOutlets() {
        $.get(serverContext + 'getUserCustomer', function (resp) {
            var rows = (resp && (resp.collection || resp.data)) || [];
            var html = '<option value="">' + esc(tr('ui.js.selectOutlet', 'Select the shop…')) + '</option>';
            rows.forEach(function (c) {
                var id = c.customerId != null ? c.customerId : c.id;
                html += '<option value="' + esc(id) + '">' + esc(c.name || ('#' + id)) + '</option>';
            });
            $('#bkOutlet').empty().append(html);
        });
    }

    /**
     * Show the outlet's credit position BEFORE any line is entered.
     *
     * A null standing means the outlet is uncapped — NOT "0 of 0". The banner stays hidden in that case, on
     * purpose: an outlet shown as breached when it has no limit teaches reps to ignore the banner, and a
     * warning that is ignored is worse than no warning.
     */
    global.bkOutletChanged = function () {
        var id = $('#bkOutlet').val();
        state.outlet = id || null;
        $('#bkCredit').hide();
        if (!id) { return; }
        $.get(serverContext + 'creditStanding?customerId=' + encodeURIComponent(id), function (resp) {
            var s = resp && resp.object;
            if (!s) { return; }                       // uncapped — nothing to say, so say nothing
            var over = s.overLimit === true;
            $('#bkCredit')
                .removeClass('alert-info alert-danger')
                .addClass(over ? 'alert-danger' : 'alert-info')
                .html(
                    '<b>' + esc(s.accountName || '') + (s.grouped ? ' ' + esc(tr('ui.js.groupAccount', '(group account)')) : '') + '</b><br>'
                    + esc(tr('ui.js.creditLimit', 'Credit limit')) + ': ' + money(s.creditLimit) + ' &nbsp;·&nbsp; '
                    + esc(tr('ui.js.owes', 'Owes')) + ': ' + money(s.owed) + ' &nbsp;·&nbsp; '
                    + '<b>' + esc(tr('ui.js.available', 'Available')) + ': ' + money(s.available) + '</b>'
                    + (over ? '<br><b>' + esc(tr('ui.js.overLimitWarn',
                        'This shop is OVER its credit limit — the order may be rejected.')) + '</b>' : ''))
                .show();
        });
    };

    // ── lines ──────────────────────────────────────────────────────────────────────────────────────────────

    function loadProducts() {
        $.get(serverContext + 'catalogProducts?size=2000', function (resp) {
            var list = (resp && resp.data && resp.data.content) ? resp.data.content
                : (Array.isArray(resp && resp.data) ? resp.data : []);
            state.products = list.filter(function (p) { return p.isActive !== false; });
            var html = '<option value="">' + esc(tr('ui.js.selectProduct', 'Select a product…')) + '</option>';
            state.products.forEach(function (p) {
                html += '<option value="' + esc(p.id) + '" data-price="' + esc(p.sellingPrice != null ? p.sellingPrice : '')
                    + '">' + esc(p.name || ('#' + p.id)) + '</option>';
            });
            $('#bkProduct').empty().append(html);
        });
    }

    /** Selecting a product pre-fills its list price — the rep may overwrite it, which is the agreed price. */
    global.bkProductChanged = function () {
        var price = $('#bkProduct option:selected').data('price');
        $('#bkPrice').val(price != null && price !== '' ? price : '');
    };

    global.bkAddLine = function () {
        var pid = $('#bkProduct').val();
        var qty = Number($('#bkQty').val() || 0);
        var price = Number($('#bkPrice').val() || 0);
        if (!pid) { return global.uiAlert(tr('ui.js.pickProductFirst', 'Choose a product first.')); }
        if (!(qty > 0)) { return global.uiAlert(tr('ui.js.qtyGreaterThanZero', 'Enter a quantity greater than zero.')); }

        var product = state.products.filter(function (p) { return String(p.id) === String(pid); })[0] || {};
        // Same product twice is a REPLACE of the quantity, not a second line: a rep correcting themselves at the
        // counter means "make it six", not "six more". The warehouse can still split it at review if it wants.
        var existing = state.lines.filter(function (l) { return String(l.productId) === String(pid); })[0];
        if (existing) {
            existing.quantity = qty;
            existing.price = price;
        } else {
            state.lines.push({ productId: Number(pid), productName: product.name || ('#' + pid), quantity: qty, price: price });
        }
        $('#bkProduct').val('');
        $('#bkQty').val('');
        $('#bkPrice').val('');
        renderLines();
    };

    global.bkRemoveLine = function (productId) {
        state.lines = state.lines.filter(function (l) { return String(l.productId) !== String(productId); });
        renderLines();
    };

    function renderLines() {
        var total = 0;
        var html = '';
        state.lines.forEach(function (l) {
            var lineTotal = Number(l.quantity) * Number(l.price || 0);
            total += lineTotal;
            html += '<tr><td>' + esc(l.productName) + '</td>'
                + '<td class="text-right">' + esc(l.quantity) + '</td>'
                + '<td class="text-right">' + money(l.price) + '</td>'
                + '<td class="text-right">' + money(lineTotal) + '</td>'
                + '<td class="text-right"><button type="button" class="btn btn-danger btn-xs" '
                + 'onclick="bkRemoveLine(' + Number(l.productId) + ')">&times;</button></td></tr>';
        });
        $('#bkLinesBody').html(html);
        $('#bkLinesEmpty').toggle(state.lines.length === 0);
        $('#bkTotal').text(money(total));
        $('#bkSubmit').prop('disabled', state.lines.length === 0);
    }

    // ── book it ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * A per-attempt idempotency key.
     *
     * A rep books on a phone, on someone else's wifi, and WILL press the button again when nothing seems to
     * happen. Two orders from one visit is a far worse failure than one that looks like it did not send — the
     * shop gets double the goods and the rep gets the blame. The key is minted once per composed order and
     * survives the retry; it is cleared only when an order is actually accepted.
     */
    function bookingKey() {
        if (!state.key) {
            state.key = 'BK-' + Date.now() + '-' + Math.floor(Math.random() * 100000);
        }
        return state.key;
    }

    global.bkSubmit = function () {
        var outletId = $('#bkOutlet').val();
        var outletName = $('#bkOutlet option:selected').text();
        if (!outletId) { return global.uiAlert(tr('ui.js.pickOutletFirst', 'Choose which shop this order is for.')); }
        if (!state.lines.length) { return; }

        $('#bkSubmit').prop('disabled', true);
        $.ajax({
            type: 'POST', url: serverContext + 'bookOrder', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({
                customerName: outletName,
                customerContact: $.trim($('#bkContact').val() || ''),
                shippingAddress: $.trim($('#bkAddress').val() || ''),
                idempotencyKey: bookingKey(),
                items: state.lines
            }),
            success: function (resp) {
                if (resp && resp.success) {
                    global.showSaleSuccess(tr('ui.js.orderBooked', 'Order booked — ')
                        + ((resp.data && resp.data.orderNo) || '') + ' · '
                        + tr('ui.js.awaitingReview', 'awaiting review'));
                    state.lines = [];
                    state.key = null;            // accepted — the NEXT order is a new one
                    renderLines();
                    loadMyOrders();
                } else {
                    global.showFormError((resp && resp.message) || tr('ui.js.couldNotBook', 'Could not book the order.'));
                    $('#bkSubmit').prop('disabled', false);
                }
            },
            error: function (xhr) {
                // The key is deliberately KEPT here, so pressing Book again replays the same order rather than
                // creating a second one.
                global.showFormError((xhr.responseJSON && xhr.responseJSON.message)
                    || tr('ui.js.couldNotBook', 'Could not book the order.'));
                $('#bkSubmit').prop('disabled', false);
            }
        });
    };

    // ── what happened to my orders ─────────────────────────────────────────────────────────────────────────

    /**
     * The rep's OWN orders — `?mine=true`, which the server resolves to the caller. This is the founding
     * requirement's *"after confirm or reject the status should be visible to the order booker"*, and a
     * rejection carries its reason inline because that is the only thing that lets the rep act on it.
     */
    function loadMyOrders() {
        $.get(serverContext + 'getOrders?mine=true&size=15', function (resp) {
            var rows = (resp && resp.data && resp.data.content) || [];
            var html = '';
            rows.forEach(function (o) {
                var status = String(o.fulfilmentStatus || '');
                var cls = status === 'REJECTED' ? 'label-danger'
                    : (status === 'PENDING_APPROVAL' ? 'label-warning' : 'label-success');
                html += '<tr><td>' + esc(o.orderNo || ('#' + o.id)) + '</td>'
                    + '<td>' + esc(o.customerName || '') + '</td>'
                    + '<td class="text-right">' + money(o.total) + '</td>'
                    + '<td><span class="label ' + cls + '">' + esc(status) + '</span>'
                    + (status === 'REJECTED' && o.rejectionReason
                        ? '<br><small class="text-danger">' + esc(o.rejectionReason) + '</small>' : '')
                    + '</td></tr>';
            });
            $('#bkMyOrdersBody').html(html);
            $('#bkMyOrdersEmpty').toggle(rows.length === 0);
        });
    }
    global.bkRefreshMyOrders = loadMyOrders;
})(window);
