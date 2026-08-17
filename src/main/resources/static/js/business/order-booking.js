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

    /**
     * The rep's outlets — their TERRITORY.
     *
     * Deliberately `/outlets`, not `getUserCustomer`. That read is scoped by `Customer.userId`, which is the
     * AUDIT field ("who created this row"): in a shop the creator and the seller are the same person, but in
     * field sales the company creates the outlet and the rep sells to it, so a booker asking for their
     * customers got back an empty list — the picker for the one screen they use.
     *
     * `/outlets` answers the question actually being asked: which shops may I book for. Identity only; an
     * outlet's balance comes from `/creditStanding`, one at a time, when a shop is chosen.
     */
    function loadOutlets() {
        $.get(serverContext + 'outlets', function (resp) {
            var rows = (resp && (resp.collection || resp.data)) || [];
            var html = '<option value="">' + esc(tr('ui.js.selectOutlet', 'Select the shop…')) + '</option>';
            var mine = rows.filter(function (c) { return c.assignedToMe; });
            var rest = rows.filter(function (c) { return !c.assignedToMe; });
            // A rep with a territory sees their own round first, under a heading, with everything unassigned
            // still reachable below it — narrowing without hiding. With no territory configured the grouping
            // collapses to a plain list, which is what a small distributor sees.
            function opts(list) {
                return list.map(function (c) {
                    return '<option value="' + esc(c.id) + '">' + esc(c.name || ('#' + c.id)) + '</option>';
                }).join('');
            }
            if (mine.length && rest.length) {
                html += '<optgroup label="' + esc(tr('ui.js.myOutlets', 'My outlets')) + '">' + opts(mine) + '</optgroup>'
                     + '<optgroup label="' + esc(tr('ui.js.otherOutlets', 'Other outlets')) + '">' + opts(rest) + '</optgroup>';
            } else {
                html += opts(rows);
            }
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
        loadStockLevels();
    }

    /**
     * On-hand for the WHOLE catalogue, in ONE call, once per visit to the screen.
     *
     * <p>Deliberately not a lookup per product pick. A rep works down a shop's list choosing item after item,
     * and a round trip on every {@code change} event is a spinner between each one on a phone on shop wifi —
     * the one place in this product where latency is most visible and least tolerable. The tenant's levels are
     * a few hundred numbers; fetching them with the product list makes the pick instant.
     *
     * <p>Silent on failure, and the badge simply stays hidden: not knowing the stock figure must never stop a
     * rep taking an order. The warehouse re-checks availability at dispatch, which is the control that matters
     * — this number is guidance at the counter, not a promise.
     */
    function loadStockLevels() {
        state.stock = {};
        $.get(serverContext + 'productStockLevels', function (resp) {
            if (!resp || resp.success !== true) { return; }
            state.stock = resp.levels || {};
        });
    }

    /** What we can honestly tell the rep is on the shelf: the SELLABLE count, not raw on-hand. */
    function sellableFor(productId) {
        var lvl = state.stock ? (state.stock[productId] || state.stock[String(productId)]) : null;
        if (!lvl) { return null; }
        // `sellable` excludes expired and held stock; on-hand alone would promise goods that cannot be picked
        // — the exact gap that produces "Insufficient stock" at dispatch on an order the rep was told was fine.
        var n = (lvl.sellable != null) ? lvl.sellable : lvl.onHand;
        return n == null ? null : Number(n);
    }

    /** Selecting a product pre-fills its list price and shows what is on the shelf. */
    global.bkProductChanged = function () {
        var price = $('#bkProduct option:selected').data('price');
        $('#bkPrice').val(price != null && price !== '' ? price : '');
        $('#bkDiscount').val(0);                 // a concession is agreed per product, never inherited

        var pid = $('#bkProduct').val();
        var $info = $('#bkStockInfo');
        if (!pid) { return $info.hide().empty(); }
        var available = sellableFor(pid);
        if (available === null) { return $info.hide().empty(); }
        $info
            .css('color', available > 0 ? '' : '#c0392b')
            .text(tr('ui.js.available', 'Available') + ': ' + available
                + (available > 0 ? '' : ' — ' + tr('ui.js.outOfStock', 'out of stock')))
            .show();
    };

    /**
     * One line's arithmetic - REUSING the sale form's sellLineMath, not a second copy of it.
     *
     * That function already settles everything this screen needs: amount vs percent, the clamp that stops a
     * discount exceeding the line, and the rounding ORDER (gross fixed to 2dp first, then the percentage taken
     * off that) which is why a 5% discount on 99.99 cannot leak extra decimals into the receivable. A private
     * copy here would be a second opinion about what "5% off" comes to, and the two would drift the first time
     * either was touched. The rep quoting at the counter and the till raising the invoice must agree.
     *
     * purchaseRate is passed as 0: it only feeds the sale form's profit figure, which a booking has no
     * business showing a rep standing in someone else's shop.
     *
     * @returns {{total:number, discount:number, receivable:number}} gross, the RESOLVED discount amount, net
     */
    function bkLineMath(price, qty, discountValue, discountType) {
        return global.sellLineMath(price, qty, 0, discountValue, discountType);
    }

    global.bkAddLine = function () {
        var pid = $('#bkProduct').val();
        var qty = Number($('#bkQty').val() || 0);
        var price = Number($('#bkPrice').val() || 0);
        if (!pid) { return global.uiAlert(tr('ui.js.pickProductFirst', 'Choose a product first.')); }
        if (!(qty > 0)) { return global.uiAlert(tr('ui.js.qtyGreaterThanZero', 'Enter a quantity greater than zero.')); }

        // Availability is a WARNING, not a block. A distributor takes an order for goods arriving Thursday all
        // the time, and a rep who cannot record what the shop asked for will write it on paper instead. The
        // warehouse decides at dispatch; O5c already handles what cannot be filled today.
        var available = sellableFor(pid);
        if (available !== null && qty > available) {
            global.showFormError(tr('ui.js.onlyNAvailable', 'Only {n} available - booking it anyway.')
                .replace('{n}', available));
        }

        var m = bkLineMath(price, qty, $('#bkDiscount').val(), $('#bkDiscountTypeDD').val());
        var product = state.products.filter(function (p) { return String(p.id) === String(pid); })[0] || {};
        // Same product twice is a REPLACE of the quantity, not a second line: a rep correcting themselves at the
        // counter means "make it six", not "six more". The warehouse can still split it at review if it wants.
        var existing = state.lines.filter(function (l) { return String(l.productId) === String(pid); })[0];
        var line = existing || { productId: Number(pid), productName: product.name || ('#' + pid) };
        line.quantity = qty;
        line.price = price;
        // The RESOLVED amount, never the percentage. The order line stores an amount because the invoice line
        // does, and keeping a percent on the wire would leave two places deciding what it is a percentage of.
        line.discount = Number(m.discount) || 0;
        if (!existing) { state.lines.push(line); }

        $('#bkProduct').val('');
        $('#bkQty').val('');
        $('#bkPrice').val('');
        $('#bkDiscount').val(0);
        $('#bkStockInfo').hide().empty();
        renderLines();
    };

    global.bkRemoveLine = function (productId) {
        state.lines = state.lines.filter(function (l) { return String(l.productId) !== String(productId); });
        renderLines();
    };

    function renderLines() {
        var total = 0, discountTotal = 0;
        var html = '';
        state.lines.forEach(function (l) {
            // The stored discount is already a resolved AMOUNT, so re-run the maths in amount mode (type 0).
            // Re-applying it as a percentage would compound it every time the table redrew.
            var m = bkLineMath(l.price, l.quantity, l.discount || 0, 0);
            total += Number(m.receivable);
            discountTotal += Number(m.discount);
            html += '<tr><td>' + esc(l.productName) + '</td>'
                + '<td class="text-right">' + esc(l.quantity) + '</td>'
                + '<td class="text-right">' + money(l.price) + '</td>'
                + '<td class="text-right">' + money(m.total) + '</td>'
                + '<td class="text-right">' + money(m.discount) + '</td>'
                + '<td class="text-right">' + money(m.receivable) + '</td>'
                + '<td class="text-right"><button type="button" class="btn btn-danger btn-xs" '
                + 'onclick="bkRemoveLine(' + Number(l.productId) + ')">&times;</button></td></tr>';
        });
        $('#bkLinesBody').html(html);
        $('#bkLinesEmpty').toggle(state.lines.length === 0);
        $('#bkDiscountTotal').text(money(discountTotal));
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
                // O7 D2c: the outlet's ID, not just its name. The name alone makes the invoice at dispatch
                // resolve the buyer by name + contact + acting user — which cannot match an outlet created by
                // someone else, so it silently bills a DUPLICATE customer with no credit limit. The rep picked
                // a real account from the list; the order must carry which one.
                customerId: Number(outletId),
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
