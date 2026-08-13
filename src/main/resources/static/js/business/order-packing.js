/**
 * OMS O7 D3 — the PACK workbench. Ilyas's screen.
 *
 * O5b made a dispatch *recordable*; it did not make it *workable*. A packer read quantities off a screen,
 * walked the shelves, and typed those numbers back in — and the Ship form defaults to "everything
 * outstanding", so the fastest correct-LOOKING action is to accept the defaults whether or not that is what
 * physically went in the box. Every O5b guard checks the quantities are arithmetically valid; none of them can
 * tell whether the goods are the right goods.
 *
 * This screen asks a different question of every item: **is this product on this order, and is any of it still
 * owed?** A wrong scan is refused at the shelf instead of by the customer.
 *
 * <h3>It writes nothing new</h3>
 * Confirm posts the identical `ShipmentDTO.Request` the Ship form already posts, so O5b's `ShipmentService`
 * stays the only writer and its guards (outstanding ≤ ordered, no empty parcel, not cancelled/returned,
 * backordered units unpickable, and now scanRequired) remain the single enforcement point. This workbench
 * cannot introduce a money or stock path because it has none of its own.
 *
 * <h3>Typing stays possible</h3>
 * A barcode can be missing or damaged, and a packer who cannot proceed will simply go back to the old screen.
 * So manual entry remains — it is just no longer the default, and the shipment records which lines were
 * verified by scan. That is the honest position: the system must not claim a verification it did not perform.
 */
(function (global) {
    'use strict';

    var state = { orderId: null, lines: [], scanRequired: false, autoConfirm: false };

    function esc(s) {
        return (global.escHtml ? global.escHtml(String(s == null ? '' : s)) : String(s == null ? '' : s));
    }

    function tr(key, fallback) {
        var v = (typeof global.t === 'function') ? global.t(key) : null;
        return (v && v !== key) ? v : fallback;
    }

    /**
     * How much of this line may still go in a box.
     *
     * Mirrors `ShipmentService.outstanding` exactly: ordered MINUS what is backordered (O5c — those units are
     * neither invoiced nor physically here, so they are not pickable) MINUS what has already shipped. The
     * server re-derives it and refuses anything larger, so this is a convenience, never the control.
     */
    function outstanding(l) {
        var invoiced = (Number(l.quantity) || 0) - (Number(l.quantityBackordered) || 0);
        return Math.max(0, invoiced - (Number(l.quantityShipped) || 0));
    }

    /** Open the workbench for one order. */
    global.openPackWorkbench = function (orderId) {
        state.orderId = orderId;
        state.lines = [];
        $.get(serverContext + 'getOrderConfig', function (cfg) {
            // Read the two packing policies the OWNER set. `scanRequired` is ALSO enforced server-side — this
            // copy only decides what the screen offers, so a packer is never shown a Confirm the server will
            // refuse. (O4's rule: don't offer what the server will reject.)
            var byKey = {};
            (((cfg || {}).data) || (cfg || {}).collection || []).forEach(function (e) {
                if (e && e.key) { byKey[e.key] = (String(e.value) === 'true'); }
            });
            state.scanRequired = byKey['order.pack.scanRequired'] === true;
            state.autoConfirm = byKey['order.pack.autoConfirm'] === true;
        }).always(function () {
            loadOrderForPacking(orderId);
        });
    };

    function loadOrderForPacking(orderId) {
        $.get(serverContext + 'getOrder?id=' + encodeURIComponent(orderId), function (resp) {
            var o = (resp && resp.data) || {};
            state.lines = (o.items || []).map(function (l) {
                return {
                    id: l.id, productId: l.productId,
                    name: l.productName || ('#' + l.productId),
                    outstanding: outstanding(l),
                    packed: 0,
                    verified: true      // a line only becomes UNVERIFIED when a human types into it
                };
            }).filter(function (l) { return l.outstanding > 0; });

            $('#packTitle').text((o.orderNo || ('#' + orderId)) + ' — ' + tr('ui.pickList', 'Pick list'));
            $('#packScanRow').toggle(true);
            $('#packScanHint').text(state.scanRequired
                ? tr('ui.js.scanRequiredHint', 'This shop requires every item to be scanned.')
                : tr('ui.js.scanOptionalHint', 'Scan items, or type the quantities.'));
            $('.formDiv').hide();
            $('#PackDiv').show();
            render();
            // focusFirstField takes a CONTAINER ELEMENT and calls querySelectorAll on it. Passing a selector
            // string threw `container.querySelectorAll is not a function` from inside this success handler —
            // which stopped jQuery firing ajaxStop, so the app's global "Please wait…" overlay never cleared
            // and the workbench was frozen for a human on every open. Focus the scan box directly: there is
            // exactly one field worth landing in here, so the container search buys nothing.
            $('#packScan').trigger('focus');
        });
    }

    /**
     * Full rebuild — on open, and after a scan (which the packer is not typing into).
     *
     * NEVER called from the quantity field's own input handler: replacing the table would destroy the very
     * input being typed into, so a packer entering "12" would get "1", lose the element, and drop the "2".
     * See {@link refreshControls}, which updates everything a keystroke can legitimately change.
     */
    function render() {
        var html = '';
        state.lines.forEach(function (l) {
            html += '<tr data-row="' + Number(l.id) + '">'
                + '<td>' + esc(l.name)
                + ' <span class="label label-warning pack-typed" style="display:' + (l.verified ? 'none' : '')
                + '">' + esc(tr('ui.js.typed', 'typed')) + '</span></td>'
                + '<td class="text-right">' + esc(l.outstanding) + '</td>'
                + '<td class="text-right" style="width:120px">'
                + '<input type="number" class="form-control input-sm pack-qty" data-line="' + Number(l.id) + '" '
                + 'min="0" max="' + esc(l.outstanding) + '" value="' + esc(l.packed) + '" inputmode="numeric">'
                + '</td></tr>';
        });
        $('#packBody').html(html);
        $('#packEmpty').toggle(state.lines.length === 0);
        return refreshControls();
    }

    /** Everything a keystroke may change, WITHOUT touching the inputs. Returns "is the order fully packed". */
    function refreshControls() {
        var allPacked = state.lines.length > 0;
        state.lines.forEach(function (l) {
            if (l.packed < l.outstanding) { allPacked = false; }
            var $row = $('#packBody tr[data-row="' + Number(l.id) + '"]');
            $row.toggleClass('success', l.packed >= l.outstanding);
            $row.find('.pack-typed').toggle(!l.verified);
        });
        // With scanning required, a hand-typed line is refused by the SERVER — so the button is disabled here
        // rather than letting the packer seal a box and then be told no.
        var blocked = state.scanRequired && state.lines.some(function (l) { return l.packed > 0 && !l.verified; });
        var nothing = !state.lines.some(function (l) { return l.packed > 0; });
        $('#packConfirm').prop('disabled', nothing || blocked);
        $('#packBlocked').toggle(blocked);
        return allPacked;
    }

    /** Typing a quantity marks the line UNVERIFIED — the system must not claim a scan that never happened. */
    $(document).on('input', '.pack-qty', function () {
        var id = Number($(this).data('line'));
        var l = lineById(id);
        if (!l) { return; }
        l.packed = Math.max(0, Math.min(Number($(this).val()) || 0, l.outstanding));
        l.verified = false;
        refreshControls();      // NOT render() — see above; that would pull the field out from under the typist
    });

    function lineById(id) {
        return state.lines.filter(function (l) { return String(l.id) === String(id); })[0];
    }

    // ── the scan ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Resolve a scanned code and put ONE (or `n*CODE`) into the box.
     *
     * Reuses `/lookupProduct` — the same barcode resolution the sell screen uses — and `parseScanEntry`, the
     * pure multiplier parser business.js already exports. O5d's design was explicit that this must not become a
     * second scanner, and the interesting part here is not resolution anyway: it is the question asked AFTER
     * resolution, which the sell screen never asks — *is this product on THIS order, and is any still owed?*
     */
    global.packScan = function () {
        var raw = $.trim($('#packScan').val() || '');
        if (!raw) { return; }
        var qty = 1, code = raw;
        if (typeof global.parseScanEntry === 'function') {
            var parsed = global.parseScanEntry(raw);
            if (parsed.error) { return packMsg(tr('ui.js.scanNotUnderstood', 'Could not read that code.'), true); }
            qty = parsed.qty; code = parsed.code;
        }
        $.get(serverContext + 'lookupProduct?code=' + encodeURIComponent(code), function (p) {
            var product = (typeof p === 'string') ? JSON.parse(p || '{}') : (p || {});
            if (!product || !product.id) {
                return packMsg(tr('ui.js.scanUnknown', 'That code is not a product here.'), true);
            }
            var l = state.lines.filter(function (x) { return String(x.productId) === String(product.id); })[0];
            if (!l) {
                // THE refusal that makes packing verifiable: right shelf, wrong order.
                return packMsg(tr('ui.js.notOnThisOrder', 'That is not on this order.'), true);
            }
            if (l.packed + qty > l.outstanding) {
                return packMsg(tr('ui.js.allAlreadyPacked', 'All of those are already in the box.'), true);
            }
            l.packed += qty;
            // Untouched by hand since the scan, so the line is still honestly verified.
            packMsg(esc(l.name) + ' +' + qty, false);
            $('#packScan').val('');
            var everythingPacked = render();
            if (everythingPacked && state.autoConfirm) {
                // The owner asked for speed over a final look. Recorded here rather than silently, so the
                // packer can see why the parcel went without them pressing anything.
                packMsg(tr('ui.js.autoDispatching', 'Everything packed — dispatching…'), false);
                global.packConfirm();
            }
        }).fail(function () {
            packMsg(tr('ui.js.scanFailed', 'Could not look that code up.'), true);
        });
    };

    function packMsg(text, isError) {
        $('#packMsg').removeClass('alert-danger alert-success')
            .addClass(isError ? 'alert-danger' : 'alert-success')
            .html(text).show();
    }

    /** Confirm → the SAME endpoint the Ship form posts. No second writer. */
    global.packConfirm = function () {
        var lines = state.lines.filter(function (l) { return l.packed > 0; })
            .map(function (l) { return { orderItemId: l.id, quantity: l.packed, verified: l.verified }; });
        if (!lines.length) { return; }
        $('#packConfirm').prop('disabled', true);
        $.ajax({
            type: 'POST', url: serverContext + 'shipOrder', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({
                id: state.orderId, lines: lines,
                carrier: $.trim($('#packCarrier').val() || ''),
                trackingNumber: $.trim($('#packTracking').val() || '')
            }),
            success: function (resp) {
                if (resp && resp.success) {
                    global.showSaleSuccess(tr('ui.js.shipmentRecorded', 'Shipment recorded: ')
                        + ((resp.data && resp.data.shipmentNo) || ''));
                    if (typeof global.showOrders === 'function') { global.showOrders(); }
                } else {
                    global.showFormError((resp && resp.message) || tr('ui.js.couldNotShip', 'Could not record the shipment.'));
                    $('#packConfirm').prop('disabled', false);
                }
            },
            error: function (xhr) {
                global.showFormError((xhr.responseJSON && xhr.responseJSON.message)
                    || tr('ui.js.couldNotShip', 'Could not record the shipment.'));
                $('#packConfirm').prop('disabled', false);
            }
        });
    };

    global.closePackWorkbench = function () {
        if (typeof global.showOrders === 'function') { global.showOrders(); }
    };
})(window);
