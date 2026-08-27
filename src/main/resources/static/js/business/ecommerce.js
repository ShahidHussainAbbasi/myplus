/*
 * E-commerce orders back-office (slice 46; rebuilt in OMS O4) — ECOMMERCE-only, on the single shared dashboard.
 * An order is a normal trade sale (reused Sell saga) plus a fulfilment lifecycle.
 * marketplace-service uses the common-web ApiResponse envelope (reads in resp.data).
 *
 * ── What O4 changed, and why ────────────────────────────────────────────────────────────────────────────────
 *
 * 1. THE LIST IS PAGED AND FILTERED BY THE SERVER (OMS-7). It used to GET every order the tenant had ever taken
 *    and render all of them. Filters are sent as query parameters, never applied to a fetched array: filtering
 *    in the browser would page the wrong thing — 25 rows fetched, then narrowed to 3, and the operator pages
 *    forward through mostly-empty screens hunting orders that were never fetched.
 *
 * 2. THE SERVER SAYS WHICH ACTIONS EXIST. This file used to carry
 *        var NEXT = { NEW:'PACKED', PACKED:'SHIPPED', SHIPPED:'DELIVERED' };
 *    a second copy of the lifecycle rules O2 made authoritative in FulfilmentStatus. The two had already
 *    drifted: Cancel was drawn for any order that was not CANCELLED or DELIVERED — including SHIPPED, which
 *    the server refuses (goods on a van are not back on the shelf), so the button returned 409. RETURN_REQUESTED
 *    was absent from the map entirely, leaving a customer's return request in a state with no actions at all.
 *    Both symptoms had one cause, so both are fixed by one change: render `o.allowedTransitions` and hold no
 *    opinion. The server still enforces the whitelist — this only stops us OFFERING what it will refuse.
 *
 * 3. REFUND AND RETURN ARE REACHABLE. Both endpoints shipped in slices 70/71, admin-gated, with the monolith
 *    proxies already relaying their refusal messages — and nothing in the UI ever called them.
 */
(function (global) {
    'use strict';

    // Current query state. Mirrors OrderQuery on the server; `size` is a request, not a promise — the server
    // caps it, and we render whatever it actually returned.
    var state = { page: 0, size: 25, totalPages: 0, totalElements: 0, last: true };

    function esc(s) {
        return (global.escHtml ? global.escHtml(String(s == null ? '' : s)) : String(s == null ? '' : s));
    }

    function money(v) {
        return v == null ? '' : Number(v).toFixed(2);
    }

    function when(v) {
        return String(v || '').replace('T', ' ').substring(0, 16);
    }

    /**
     * May the signed-in user reverse money and stock?
     *
     * Set by a `sec:authorize` script in the dashboard template (the established pattern here — see
     * window.canVoidInvoice / canClosePeriod), mirroring marketplace-service's ADMIN_PRIVILEGE gate on
     * cancel/return/refund. This decides whether a BUTTON is drawn, nothing more: the server enforces the
     * same rule and answers 403 regardless of what the browser believes.
     */
    function canReverse() {
        return global.canReverseOrder === true;
    }

    /**
     * May the signed-in user RELEASE a booked order — confirm it, or reject it back to the booker?
     *
     * Separate from {@link canReverse} because the server's two gates are separate: cancel/return/refund want
     * ADMIN_PRIVILEGE, while confirm/reject want ROLE_OWNER, ADMIN_PRIVILEGE or SUPER_PRIVILEGE. Reusing the
     * reversal flag here would have hidden the review buttons from a SUPER who is allowed to press them.
     * Set by a `sec:authorize` script in the dashboard, the same way canReverseOrder is; the server enforces
     * the rule regardless of what the browser believes.
     */
    function canReview() {
        return global.canReviewOrder === true;
    }

    /**
     * One action button.
     *
     * <p>{@code data-act} + {@code data-order} rather than an id, because these are rendered per ROW: an id
     * would be duplicated down the grid, which is invalid and makes a test select the wrong order's button.
     * The attributes also give the gate a stable handle that does not depend on the button's label, so a
     * translation or a wording change cannot silently stop the test exercising the thing it names.
     */
    function btn(cls, act, orderId, onclick, label) {
        return "<button class='btn btn-xs " + cls + "'"
            + " data-act='" + esc(act) + "' data-order='" + Number(orderId) + "'"
            + ' onclick="' + onclick + '">' + esc(label) + '</button> ';
    }

    global.showOrders = function () {
        $('.formDiv').hide();
        $('#OrdersDiv').show();
        closeOrderDetail();
        state.page = 0;
        loadOrders();
    };

    // ── query string ────────────────────────────────────────────────────────────────────────────────────────

    function filterParams() {
        var p = { page: state.page, size: state.size };
        var q = $.trim($('#ordFilterQ').val() || '');
        if (q) { p.q = q; }
        var st = $('#ordFilterStatus').val(); if (st) { p.status = st; }
        var pay = $('#ordFilterPayment').val(); if (pay) { p.paymentStatus = pay; }
        var src = $('#ordFilterSource').val(); if (src) { p.source = src; }
        // The date pickers write dd-MM-yyyy (the wire format contract in /js/common/date-picker.js); the API
        // takes ISO. Convert here rather than loosening the server's parser.
        var from = toIso($('#ordFilterFrom').val()); if (from) { p.from = from; }
        var to = toIso($('#ordFilterTo').val()); if (to) { p.to = to; }
        if ($('#ordFilterLate').is(':checked')) { p.late = true; }   // O5c: promised before today, not complete
        return p;
    }

    // The picker's dd-MM-yyyy → the ISO the API takes. Delegated to /js/common/date-picker.js, which owns
    // the wire format: this was a second, character-identical reading of it.
    function toIso(v) { return global.dpToIso ? global.dpToIso(v) : ''; }

    global.applyOrderFilters = function () {
        state.page = 0;         // a new filter starts at the beginning; page 4 of the old result set is meaningless
        closeOrderDetail();
        loadOrders();
    };

    global.clearOrderFilters = function () {
        $('#ordFilterQ').val('');
        $('#ordFilterStatus,#ordFilterPayment,#ordFilterSource').val('');
        $('#ordFilterFrom,#ordFilterTo').val('');
        applyOrderFilters();
    };

    global.ordersPage = function (delta) {
        var next = state.page + delta;
        if (next < 0 || (delta > 0 && state.last)) { return; }
        state.page = next;
        closeOrderDetail();
        loadOrders();
    };

    // ── list ────────────────────────────────────────────────────────────────────────────────────────────────

    function loadOrders() {
        $.get(serverContext + 'getOrders', filterParams(), function (resp) {
            var pageData = (resp && resp.data) ? resp.data : {};
            var list = pageData.content || [];
            state.totalPages = pageData.totalPages || 0;
            state.totalElements = pageData.totalElements || 0;
            state.last = pageData.last !== false;

            var $b = $('#ordersBody').empty();
            $('#ordersEmpty').toggle(list.length === 0);
            list.forEach(function (o) { $b.append(rowFor(o)); });
            renderPager();
        }).fail(function () { showFormError(t('ui.js.couldNotLoadOrders')); });
    }
    global.loadOrders = loadOrders;

    function rowFor(o) {
        var tr = $('<tr>');
        // The order NUMBER is the merchant-facing identity (O2); it opens the detail.
        tr.append($('<td>').html('<a href="#" onclick="openOrderDetail(' + Number(o.id) + ');return false">'
            + esc(o.orderNo || ('#' + o.id)) + '</a>'));
        tr.append($('<td>').text(o.invoiceNo || ''));
        tr.append($('<td>').text(o.customerName || ''));
        tr.append($('<td>').text(money(o.total)));
        tr.append($('<td>').text((o.paymentMode || '') + (o.paymentStatus ? ' / ' + o.paymentStatus : '')));
        tr.append($('<td>').text(o.fulfilmentStatus || ''));
        // O5c: promised date + aging. `late` is derived server-side on every read, so it cannot drift the way a
        // stored flag would once the clock passes the promise.
        var promised = $('<td>').text(o.promisedDate || '');
        if (o.late === true) {
            promised.css({ color: '#c0392b', fontWeight: '600' })
                    .attr('title', t('ui.js.pastPromisedDate') || 'Past its promised date');
            tr.css('background', '#fdecea');
        }
        tr.append(promised);
        tr.append($('<td>').text(when(o.createdAt)));
        tr.append($('<td>').html(actionsFor(o)));
        return tr;
    }

    /**
     * One button per SERVER-permitted transition, and nothing else.
     *
     * This is the whole point of O4: the browser no longer decides what is possible. A terminal order gets its
     * status as plain text because `allowedTransitions` is empty — not because this function knows that
     * CANCELLED is terminal.
     */
    /**
     * The buttons for one order, drawn from the SERVER's allowedTransitions — with the review decisions
     * routed to their own endpoints.
     *
     * <p>O7 D1 shipped the approval gate server-side with no reviewer UI, and this function was the reason it
     * was invisible: it renders every allowed transition as a generic "Mark X". For a PENDING_APPROVAL order
     * that produced <b>Mark NEW</b> and <b>Mark REJECTED</b> — buttons the server always refuses, because
     * confirming is a review decision and not a status change (it carries an admin gate and, on reject, the
     * REASON the booker needs). So the grid offered two buttons that could not work and hid the two that
     * could. Same one state along: a REJECTED order needs `resubmit`, not "Mark PENDING_APPROVAL".
     *
     * <p>The rule this encodes: <b>a transition the generic endpoint refuses must not be drawn as a generic
     * button.</b> The server keeps publishing those moves in allowedTransitions — they ARE legal, which is why
     * `resubmit` and `confirm` can reach them — so the filtering belongs here, next to the buttons.
     */
    function actionsFor(o) {
        var moves = o.allowedTransitions || [];
        var from = o.fulfilmentStatus;
        var id = Number(o.id);
        var html = '';

        // ── review decisions, on their own endpoints ────────────────────────────────────────────────────
        if (from === 'PENDING_APPROVAL' && canReview()) {
            html += btn('btn-success', 'confirm', id, 'confirmBookedOrder(' + id + ')', t('ui.js.confirm') || 'Confirm');
            html += btn('btn-warning', 'reject', id, 'rejectBookedOrder(' + id + ')', t('ui.js.reject') || 'Reject');
        }
        // Resubmit is NOT admin-gated: it is the booker's own move after revising a rejected order, and
        // gating it would leave the rejection with nobody able to answer it.
        if (from === 'REJECTED') {
            html += btn('btn-primary', 'resubmit', id, 'resubmitBookedOrder(' + id + ')', t('ui.js.resubmit') || 'Resubmit');
        }

        // ── everything else: generic status moves ───────────────────────────────────────────────────────
        html += moves.map(function (to) {
            // Handled above by a named endpoint — never draw the generic form of these.
            if (from === 'PENDING_APPROVAL' && (to === 'NEW' || to === 'REJECTED')) { return ''; }
            if (from === 'REJECTED' && to === 'PENDING_APPROVAL') { return ''; }
            // CANCELLED and RETURNED reverse money and stock, so they carry the server's admin gate; showing
            // them to a packer would be offering a 403.
            var reversal = (to === 'CANCELLED' || to === 'RETURNED');
            if (reversal && !canReverse()) { return ''; }
            return btn(reversal ? 'btn-danger' : 'btn-primary', 'mark-' + to, id,
                "moveOrder(" + id + ",'" + esc(to) + "')",
                (t('ui.js.mark') || 'Mark') + ' ' + to);
        }).join('');

        // Nothing this user may do — say what the order IS, so the cell is never blank. A booker looking at
        // their own order in review lands here, which is the point: they can see it, they cannot release it.
        if (!html) { return '<span class="text-muted">' + esc(from || '') + '</span>'; }
        return html;
    }

    function renderPager() {
        var shown = state.totalElements;
        $('#ordersPager').toggle(shown > 0);
        $('#ordersCount').text(
            (t('ui.js.page') || 'Page') + ' ' + (state.page + 1)
            + ' / ' + Math.max(state.totalPages, 1)
            + ' — ' + shown + ' ' + (t('ui.js.orders') || 'orders'));
        $('#ordPrev').prop('disabled', state.page <= 0);
        $('#ordNext').prop('disabled', !!state.last);
    }

    // ── transitions ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Move an order to a state the SERVER offered. A reversal is confirmed first because it puts stock back and
     * refunds money — uiConfirm, never window.confirm (the platform confirm-dialog contract).
     */
    global.moveOrder = function (id, status) {
        var reversal = (status === 'CANCELLED' || status === 'RETURNED');
        if (!reversal) { return postStatus(id, status); }

        uiConfirm({
            title: status === 'CANCELLED' ? t('ui.js.cancelThisOrder') : t('ui.js.processThisReturn'),
            message: t('ui.js.theOrderIsCancelledAndItsStock'),
            confirmText: status === 'CANCELLED' ? t('ui.js.cancelOrder') : t('ui.js.processReturn'),
            cancelText: t('ui.js.keepOrder'),
            tone: 'danger'
        }).then(function (ok) { if (ok) { postStatus(id, status); } });
    };

    function postStatus(id, status) {
        $.ajax({
            type: 'POST', url: serverContext + 'updateOrderStatus', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ id: id, status: status }),
            success: function (resp) {
                if (resp && resp.success) {
                    showSaleSuccess(t('ui.js.order') + status);
                    loadOrders();
                    if ($('#orderDetail').is(':visible')) { openOrderDetail(id); }
                } else {
                    // Relay the server's own wording ("a CANCELLED order cannot become SHIPPED"): it explains
                    // what to do differently, which a generic message does not.
                    showFormError((resp && resp.message) || t('ui.js.couldNotUpdateTheOrder'));
                }
            },
            error: function (xhr) {
                showFormError((xhr.responseJSON && xhr.responseJSON.message) || t('ui.js.couldNotUpdateTheOrder'));
            }
        });
    }

    // ── O7 D1: the review decisions ─────────────────────────────────────────────────────────────────────
    //
    // Their own endpoints, not updateOrderStatus, because releasing a booker's order is the control the whole
    // pre-sales model rests on: the generic path would bypass the admin gate AND lose the rejection reason.

    /** POST one of the review endpoints and refresh, relaying the server's own wording on refusal. */
    function postReview(path, body, id, okKey) {
        $.ajax({
            type: 'POST', url: serverContext + path, contentType: 'application/json', dataType: 'json',
            data: JSON.stringify(body),
            success: function (resp) {
                if (resp && resp.success) {
                    showSaleSuccess(t(okKey) || okKey);
                    loadOrders();
                    if ($('#orderDetail').is(':visible')) { openOrderDetail(id); }
                } else {
                    // e.g. "This order is not awaiting review" — tells the reviewer what changed under them.
                    showFormError((resp && resp.message) || t('ui.js.couldNotUpdateTheOrder'));
                }
            },
            error: function (xhr) {
                showFormError((xhr.responseJSON && xhr.responseJSON.message) || t('ui.js.couldNotUpdateTheOrder'));
            }
        });
    }

    /** Release a booked order for picking. */
    global.confirmBookedOrder = function (id) {
        postReview('confirmOrder', { id: id }, id, 'ui.js.orderConfirmed');
    };

    /**
     * Send a booked order back to the booker.
     *
     * The reason is REQUIRED, not decoration: a rejection with no reason gives the rep nothing to revise, and
     * the order comes straight back. uiPromptConfirm, never window.prompt (the platform confirm contract).
     */
    global.rejectBookedOrder = function (id) {
        uiPromptConfirm({
            title: t('ui.js.rejectThisOrder') || 'Send this order back to the booker?',
            message: t('ui.js.theBookerSeesThisReason')
                || 'The booker sees this reason and can revise and resubmit the order.',
            input: { label: t('ui.js.reason') || 'Reason' },
            confirmText: t('ui.js.reject') || 'Reject',
            cancelText: t('ui.js.keepInReview') || 'Keep in review',
            tone: 'warning'
        }).then(function (reason) {
            if (reason === null) { return; }                 // dismissed
            if (!String(reason).trim()) {                    // confirmed with an empty box
                showFormError(t('ui.js.aRejectionNeedsAReason') || 'A rejection needs a reason.');
                return;
            }
            postReview('rejectOrder', { id: id, reason: String(reason).trim() }, id, 'ui.js.orderRejected');
        });
    };

    /** The booker's own move: a revised order goes back for review, never straight to NEW. */
    global.resubmitBookedOrder = function (id) {
        postReview('resubmitOrder', { id: id }, id, 'ui.js.orderResubmitted');
    };

    // Kept for callers that still say cancelOrder(id) — one implementation, no second copy of the rules.
    global.cancelOrder = function (id) { global.moveOrder(id, 'CANCELLED'); };
    global.advanceOrder = function (id, status) { global.moveOrder(id, status); };

    // ── detail ──────────────────────────────────────────────────────────────────────────────────────────────

    global.closeOrderDetail = function () {
        $('#orderDetail').hide();
        $('#orderDetailBody').empty();
    };

    global.openOrderDetail = function (id) {
        $.get(serverContext + 'getOrder', { id: id }, function (resp) {
            if (!resp || !resp.success || !resp.data) {
                showFormError(t('ui.js.couldNotLoadOrders'));
                return;
            }
            renderDetail(resp.data);
        }).fail(function () { showFormError(t('ui.js.couldNotLoadOrders')); });
    };

    function renderDetail(o) {
        $('#orderDetailTitle').text((o.orderNo || ('#' + o.id)) + ' — ' + (o.customerName || ''));

        var html = '<div class="table-scroll"><table class="table table-condensed" style="margin-top:10px">'
            + '<thead><tr><th>' + esc(t('ui.js.product') || 'Product') + '</th><th class="text-right">'
            + esc(t('ui.js.quantity') || 'Qty') + '</th><th class="text-right">' + esc(t('ui.js.rate') || 'Price')
            + '</th><th class="text-right">' + esc(t('ui.js.total') || 'Total') + '</th></tr></thead><tbody>';

        var lines = o.items || [];
        if (!lines.length) {
            html += '<tr><td colspan="4" class="text-muted">' + esc(t('ui.js.noLinesRecorded')
                || 'No lines recorded for this order.') + '</td></tr>';
        }
        lines.forEach(function (l) {
            var lineTotal = (Number(l.price) || 0) * (Number(l.quantity) || 0);
            // productName is snapshotted at write (V14); pre-V14 rows have none, so show the id rather than
            // asking the catalog what the product is called TODAY — that is not what the order sold.
            // O5b: show shipped-of-ordered so a partly dispatched line is legible at a glance.
            var shippedQty = Number(l.quantityShipped) || 0;
            var qtyCell = shippedQty > 0 && shippedQty < Number(l.quantity)
                ? esc(shippedQty) + ' / ' + esc(l.quantity)
                : esc(l.quantity);
            html += '<tr><td>' + esc(l.productName || ('#' + l.productId)) + '</td>'
                + '<td class="text-right">' + qtyCell + '</td>'
                + '<td class="text-right">' + money(l.price) + '</td>'
                + '<td class="text-right">' + money(lineTotal) + '</td></tr>';
        });
        html += '</tbody></table></div>';

        // OMS O5b — the parcels this order has gone out in.
        var shipments = o.shipments || [];
        if (shipments.length) {
            html += '<div style="margin-top:12px"><b>' + esc(t('ui.js.shipments') || 'Shipments') + '</b><ul>';
            shipments.forEach(function (s) {
                var units = (s.lines || []).reduce(function (n, l) { return n + (Number(l.quantity) || 0); }, 0);
                html += '<li>' + esc(s.shipmentNo) + ' — ' + units + ' ' + esc(t('ui.js.units') || 'item(s)')
                    + (s.carrier ? ' · ' + esc(s.carrier) : '')
                    + (s.trackingNumber ? ' · ' + esc(s.trackingNumber) : '')
                    + ' · ' + esc(when(s.shippedAt)) + '</li>';
            });
            html += '</ul></div>';
        }

        html += '<div style="display:flex;flex-wrap:wrap;gap:30px;margin-top:10px">';
        html += '<div><b>' + esc(t('ui.js.totals') || 'Totals') + '</b><br>'
            + esc(t('ui.js.subTotal') || 'Subtotal') + ': ' + money(o.subTotal) + '<br>'
            + (Number(o.discountAmount) > 0 ? (esc(t('ui.js.discount') || 'Discount') + ': -' + money(o.discountAmount)
                + (o.couponCode ? ' (' + esc(o.couponCode) + ')' : '') + '<br>') : '')
            + esc(t('ui.js.tax') || 'Tax') + ': ' + money(o.taxTotal) + '<br>'
            + esc(t('ui.js.shipping') || 'Shipping') + ': ' + money(o.shippingFee)
            + (o.shippingMethod ? ' (' + esc(o.shippingMethod) + ')' : '') + '<br>'
            + '<b>' + esc(t('ui.js.total') || 'Total') + ': ' + money(o.total) + '</b></div>';

        html += '<div><b>' + esc(t('ui.js.payment') || 'Payment') + '</b><br>'
            + esc(o.paymentMode || '') + ' / ' + esc(o.paymentStatus || '') + '<br>'
            + (o.paymentRef ? esc(o.paymentRef) + '<br>' : '')
            + (Number(o.refundedAmount) > 0
                ? (esc(t('ui.js.refunded') || 'Refunded') + ': ' + money(o.refundedAmount) + '<br>') : '')
            + esc(t('ui.js.refundable') || 'Refundable') + ': ' + money(o.refundableAmount) + '</div>';

        html += '<div><b>' + esc(t('ui.js.delivery') || 'Delivery') + '</b><br>'
            + esc(o.shippingAddress || '—') + '<br>'
            + esc(t('ui.js.channel') || 'Channel') + ': ' + esc(o.source || '') + '<br>'
            + esc(t('ui.js.books') || 'Books') + ': ' + esc(o.booksStatus || '') + '</div>';
        html += '</div>';

        // Timeline — order_events, written on every status change since slice 46 and shown here for the first
        // time. Until O4 the SHOPPER's tracking page was the only place it appeared.
        html += '<div style="margin-top:14px"><b>' + esc(t('ui.js.timeline') || 'Timeline') + '</b>'
            + '<ul id="orderTimeline" style="margin-top:6px">';
        var events = o.timeline || [];
        if (!events.length) {
            html += '<li class="text-muted">' + esc(t('ui.js.noEventsYet') || 'No events yet.') + '</li>';
        }
        events.forEach(function (e) {
            html += '<li>' + esc(when(e.at)) + ' — <b>' + esc(e.status || '') + '</b>'
                + (e.note ? ' — ' + esc(e.note) : '') + '</li>';
        });
        html += '</ul></div>';

        // Actions, again straight from the server's list.
        html += '<div id="orderDetailActions" style="margin-top:12px">' + actionsFor(o);
        // OMS O5b: Ship replaces "Mark SHIPPED". The order becomes SHIPPED because a parcel was RECORDED —
        // the status is derived from what went out, so there is no button that can claim a dispatch.
        if (outstandingUnits(o) > 0 && o.fulfilmentStatus !== 'CANCELLED' && o.fulfilmentStatus !== 'RETURNED') {
            // O7 D3: PACK comes first and is the primary action — it verifies each item against this order as
            // it goes in the box, which is the one error the Ship form cannot catch (right count, wrong goods).
            // Ship stays, deliberately: a barcode can be missing or damaged, and a packer who cannot proceed
            // will find some other way round. Typing is no longer the DEFAULT; it is still possible, and the
            // shipment records which lines were actually scanned.
            html += " <button class='btn btn-xs btn-primary' id='orderPackBtn' onclick=\"openPackWorkbench("
                + Number(o.id) + ')">' + esc(t('ui.js.pack') || 'Pack') + '</button>';
            html += " <button class='btn btn-xs btn-success' id='orderShipBtn' onclick=\"openShipForm("
                + Number(o.id) + ')">' + esc(t('ui.js.ship') || 'Ship') + '</button>';
        }
        // O7 D4: a dispatched parcel needs its outcome keying when the driver gets back. Drawn only when
        // there is something undelivered to key, so the button never appears on a settled order.
        if ((o.shipments || []).some(function (sh) { return sh.status === 'DISPATCHED'; })) {
            html += " <button class='btn btn-xs btn-info' id='orderDeliveryBtn' onclick=\"openDeliveryForm("
                + Number(o.id) + ')">' + esc(t('ui.js.recordDelivery') || 'Record delivery') + '</button>';
        }
        // OMS O8 — the per-stop slip. Drawn only once the order HAS an invoice, because the challan carries the
        // invoice number the shopkeeper matches on: offered before dispatch it would print a document with the
        // one field that makes it useful left blank.
        //
        // Both buttons, deliberately. Print is for the copy that goes out with the goods and gets signed;
        // download is for the office, which until now could keep no record of what it handed over.
        if (o.invoiceNo) {
            // Single-quoted JS string inside the double-quoted attribute, with the value escaped: an invoice
            // number is server-generated (INV-000123) but it is still data being written into markup, and the
            // rule here is escape-always rather than escape-when-it-looks-risky.
            var invArg = "'" + esc(String(o.invoiceNo)) + "'";
            html += " <button class='btn btn-xs btn-default' data-act='challan' data-order='" + Number(o.id) + "'"
                + ' onclick="printChallan(' + invArg + ')">'
                + esc(t('ui.js.printChallan') || 'Print challan') + '</button> ';
            html += "<button class='btn btn-xs btn-default' data-act='challan-pdf' data-order='" + Number(o.id) + "'"
                + ' onclick="downloadChallan(' + invArg + ')">'
                + esc(t('ui.js.challanPdf') || 'Challan PDF') + '</button> ';
        }
        if (canReverse() && Number(o.refundableAmount) > 0 && o.paymentMode === 'CARD') {
            html += " <button class='btn btn-xs btn-warning' id='orderRefundBtn' onclick=\"refundOrderPrompt("
                + Number(o.id) + ',' + Number(o.refundableAmount) + ')">'
                + esc(t('ui.js.refund') || 'Refund') + '</button>';
        }
        html += '</div>';

        $('#orderDetailBody').html(html);
        $('#orderDetail').show();
    }

    /**
     * Refund, defaulting to what the SERVER says is still refundable. The default comes from the response, not
     * from a browser-side (total - refunded): OrderService.refund rejects an over-refund, and two derivations of
     * one number is how a UI comes to offer an amount the server refuses.
     */
    global.refundOrderPrompt = function (id, refundable) {
        uiPromptConfirm({
            title: t('ui.js.refundThisOrder') || 'Refund this order?',
            message: t('ui.js.refundAmountLeaveAsIs')
                || 'Amount to refund. Leave blank to refund everything still outstanding.',
            // uiPromptConfirm resolves the entered STRING, or null when cancelled (confirm-dialog.js).
            input: { label: (t('ui.js.amount') || 'Amount'), value: String(refundable) },
            confirmText: t('ui.js.refund') || 'Refund',
            tone: 'danger'
        }).then(function (entered) {
            if (entered === null || entered === false) { return; }   // cancelled
            var amount = $.trim(String(entered || ''));
            // Blank means "everything still outstanding" — the server treats a missing amount as a full refund,
            // so we send nothing rather than guessing a number here.
            if (!amount) { amount = null; }
            $.ajax({
                type: 'POST', url: serverContext + 'refundOrder', contentType: 'application/json', dataType: 'json',
                data: JSON.stringify({ id: id, amount: amount }),
                success: function (resp) {
                    if (resp && resp.success) {
                        showSaleSuccess(t('ui.js.refundIssued') || 'Refund issued.');
                        loadOrders();
                        openOrderDetail(id);
                    } else {
                        // e.g. "a COD order cannot be card-refunded" — the server's reason, not ours.
                        showFormError(apiMessage(resp, 'Could not refund the order.'));
                    }
                },
                error: function (xhr) {
                    showFormError((xhr.responseJSON && xhr.responseJSON.message) || 'Could not refund the order.');
                }
            });
        });
    };

    // ── shipping (OMS O5b) ──────────────────────────────────────────────────────────────────────────────────

    /** Units still to go across the whole order. */
    function outstandingUnits(o) {
        return (o.items || []).reduce(function (n, l) {
            return n + Math.max(0, (Number(l.quantity) || 0) - (Number(l.quantityShipped) || 0));
        }, 0);
    }

    /**
     * The dispatch form: one row per line still owed, defaulting to everything outstanding — the common case is
     * "send what's left". Carrier and tracking are free text, which is what a small merchant actually has.
     */
    global.openShipForm = function (id) {
        $.get(serverContext + 'getOrder', { id: id }, function (resp) {
            if (!resp || !resp.success || !resp.data) { showFormError(t('ui.js.couldNotLoadOrders')); return; }
            var o = resp.data;
            var rows = (o.items || []).filter(function (l) {
                return (Number(l.quantity) || 0) - (Number(l.quantityShipped) || 0) > 0;
            });
            if (!rows.length) { showFormError(t('ui.js.nothingOutstanding') || 'Nothing left to ship.'); return; }

            var html = '<div class="table-scroll"><table class="table table-condensed"><thead><tr>'
                + '<th>' + esc(t('ui.js.product') || 'Product') + '</th>'
                + '<th class="text-right">' + esc(t('ui.js.outstanding') || 'Outstanding') + '</th>'
                + '<th class="text-right">' + esc(t('ui.js.shipNow') || 'Ship now') + '</th>'
                + '</tr></thead><tbody>';
            rows.forEach(function (l) {
                var left = (Number(l.quantity) || 0) - (Number(l.quantityShipped) || 0);
                html += '<tr><td>' + esc(l.productName || ('#' + l.productId)) + '</td>'
                    + '<td class="text-right">' + left + '</td>'
                    + '<td class="text-right"><input type="number" min="0" max="' + left + '" value="' + left
                    + '" class="form-control ship-qty" data-line="' + Number(l.id)
                    + '" style="width:90px;display:inline-block"></td></tr>';
            });
            html += '</tbody></table></div>'
                + '<div class="form-inline" style="margin-top:8px">'
                + '<input type="text" class="form-control" id="shipCarrier" placeholder="'
                + esc(t('ui.js.carrier') || 'Carrier') + '" style="width:180px"> '
                + '<input type="text" class="form-control" id="shipTracking" placeholder="'
                + esc(t('ui.js.trackingNumber') || 'Tracking number') + '" style="width:220px"> '
                + '<button class="btn btn-success" id="shipConfirmBtn" onclick="submitShipment(' + Number(id)
                + ')">' + esc(t('ui.js.recordShipment') || 'Record shipment') + '</button> '
                + '<button class="btn btn-default" onclick="openOrderDetail(' + Number(id) + ')">'
                + esc(t('ui.js.cancel') || 'Cancel') + '</button></div>';

            $('#orderDetailTitle').text((o.orderNo || ('#' + o.id)) + ' — ' + (t('ui.js.ship') || 'Ship'));
            $('#orderDetailBody').html(html);
            $('#orderDetail').show();
        }).fail(function () { showFormError(t('ui.js.couldNotLoadOrders')); });
    };

    global.submitShipment = function (id) {
        var lines = [];
        $('.ship-qty').each(function () {
            var qty = Number($(this).val()) || 0;
            if (qty > 0) lines.push({ orderItemId: Number($(this).data('line')), quantity: qty });
        });
        if (!lines.length) { showFormError(t('ui.js.nothingToShip') || 'Enter a quantity for at least one line.'); return; }

        $.ajax({
            type: 'POST', url: serverContext + 'shipOrder', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({
                id: id, lines: lines,
                carrier: $('#shipCarrier').val(), trackingNumber: $('#shipTracking').val()
            }),
            success: function (resp) {
                if (resp && resp.success) {
                    showSaleSuccess((t('ui.js.shipmentRecorded') || 'Shipment recorded: ')
                        + ((resp.data && resp.data.shipmentNo) || ''));
                    loadOrders();
                    openOrderDetail(id);
                } else {
                    // e.g. "Cannot ship 6 of Widget — only 5 still to go" — the server's own wording.
                    showFormError(apiMessage(resp, 'Could not record the shipment.'));
                }
            },
            error: function (xhr) {
                showFormError((xhr.responseJSON && xhr.responseJSON.message) || 'Could not record the shipment.');
            }
        });
    };

    // ── CSV export ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Export what the FILTER selected, not what is on screen. An operator exporting "March, unpaid" wants March,
     * not 25 rows of March. The same server cap applies, so a very large selection is refused with a message
     * rather than silently truncated — a short file that looks complete is worse than no file.
     */
    global.exportOrdersCsv = function () {
        var params = filterParams();
        params.page = 0;
        params.size = 100;                       // OrderQuery.MAX_SIZE; the server clamps anything larger anyway
        $.get(serverContext + 'getOrders', params, function (resp) {
            var pageData = (resp && resp.data) ? resp.data : {};
            var rows = pageData.content || [];
            if (!rows.length) { showFormError(t('ui.js.nothingToExport') || 'Nothing to export.'); return; }
            if ((pageData.totalElements || 0) > rows.length) {
                showFormError((t('ui.js.tooManyToExport')
                    || 'That selection is too large to export. Narrow the filter (dates, status) and try again.'));
                return;
            }
            var head = ['Order', 'Invoice', 'Buyer', 'Contact', 'Channel', 'Payment', 'PaymentStatus',
                        'Status', 'Subtotal', 'Tax', 'Shipping', 'Discount', 'Total', 'Refunded', 'Created'];
            var lines = [head.join(',')];
            rows.forEach(function (o) {
                lines.push([o.orderNo, o.invoiceNo, o.customerName, o.customerContact, o.source, o.paymentMode,
                            o.paymentStatus, o.fulfilmentStatus, o.subTotal, o.taxTotal, o.shippingFee,
                            o.discountAmount, o.total, o.refundedAmount, when(o.createdAt)].map(csvCell).join(','));
            });
            downloadCsv(lines.join('\r\n'), 'orders.csv');
        }).fail(function () { showFormError(t('ui.js.couldNotLoadOrders')); });
    };

    function csvCell(v) {
        var s = (v == null) ? '' : String(v);
        // Quote when the value could break the row, and double any embedded quote.
        return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
    }

    function downloadCsv(text, filename) {
        // BOM so Excel opens UTF-8 correctly — without it, accented buyer names arrive mangled.
        var blob = new Blob(['﻿' + text], { type: 'text/csv;charset=utf-8;' });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }

    // ── POS hook — REMOVED by OMS O5e step 3 (closes OMS-5) ─────────────────────────────────────────────────
    //
    // `recordOrder(invoiceNo)` used to live here: main.js called it after a successful addSell, and it posted
    // {invoiceNo, customerName, total} where `total` was this file's own sum of `global.data` — the cart, in the
    // browser. Three defects in one function:
    //
    //   * the total was CLIENT-COMPUTED (gap B), the very thing O1 removed from the storefront;
    //   * it sent no line items (gap A), so the order failed cancel/return's `!items.isEmpty()` guard and a POS
    //     order could never restore stock;
    //   * it ran AFTER the sale, from the browser (gap E) — close the tab and the sale survived without it.
    //
    // The order is now created by `SellController.addSell` → `PosOrderRecorder`, from the invoice
    // business-service actually wrote. The browser reports neither the sale nor the order.
})(window);
