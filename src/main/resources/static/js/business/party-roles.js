/**
 * DR-2 — one partner, two roles, from the screen.
 *
 *   • "+ Supplier" on a customer row / "+ Customer" on a supplier row: opens the OTHER form with the details copied.
 *     On save, DR-1 matching (phone, CNIC / NTN) puts both records on one partner — no separate link step.
 *   • Link / Unlink inside the 360 popup (owner/admin — the popup itself is), for what matching cannot decide:
 *     two different numbers that are one business, or one number shared by two businesses.
 *
 * Link and unlink change who a record BELONGS to, never its balances: sales, purchases and dues stay on the record.
 * Server: POST /partyLink {customerId, venderId}, POST /partyUnlink {role, id} — both owner/admin, audited.
 */
(function (global, $) {
    'use strict';

    function msg(key, fallback) {
        return (typeof global.tHas === 'function' && typeof global.t === 'function' && global.tHas(key)) ? global.t(key) : fallback;
    }

    function postJson(path, body) {
        return $.ajax({ type: 'POST', url: serverContext + path, contentType: 'application/json',
                        dataType: 'json', data: JSON.stringify(body) });
    }

    /** Re-read the grid on screen so its badges follow a link / unlink. */
    function refreshGrid() {
        if ((global.tableV === 'Customer' || global.tableV === 'Vender') && typeof global.loadDataTable === 'function') {
            global.loadDataTable();
        }
    }

    // ── "+ Supplier" / "+ Customer" ────────────────────────────────────────────────────────────────────────────────

    /** Switch to the other register screen, open a NEW record, and copy the details in. Company stays the user's pick (D7). */
    function openAs(div, entity, title, values) {
        if (typeof global.snavGo === 'function') global.snavGo('registrationType', div, 'snavRegister');
        // The section switch starts the grid load; the form is opened after it, so the reset in newEntity runs first
        // and our values land on a clean form.
        setTimeout(function () {
            global.newEntity(entity);
            $('#' + entity + 'ModalTitle').text(title);
            Object.keys(values).forEach(function (id) {
                if (values[id]) $('#' + id).val(values[id]);
            });
        }, 80);
    }

    $(document).on('click', '[data-action="add-as-supplier"]', function () {
        var d = this.dataset;
        openAs('VenderDiv', 'Vender', msg('ui.js.drNewSupplierFrom', 'New supplier — details copied from the customer'), {
            venderName: d.name, venderMobile: d.contact, venderEmail: d.email, venderAddress: d.address, venderCnicNtn: d.tax
        });
    });

    $(document).on('click', '[data-action="add-as-customer"]', function () {
        var d = this.dataset;
        openAs('CustomerDiv', 'Customer', msg('ui.js.drNewCustomerFrom', 'New customer — details copied from the supplier'), {
            customerName: d.name, contact: d.contact, email: d.email, address: d.address, customerCnic: d.tax
        });
    });

    // ── Link / Unlink inside the 360 popup ────────────────────────────────────────────────────────────────────────

    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text != null) e.textContent = text;
        return e;
    }

    function reopen360(partyId) {
        $('.c360-card .c360-x').trigger('click');
        if (typeof global.openContact360 === 'function') global.openContact360(partyId);
    }

    /** One line under the buttons for the server's answer — a toast would sit behind the popup. */
    function say(box, text, bad) {
        var line = box.querySelector('.dr-say') || box.appendChild(el('div', 'dr-say'));
        line.textContent = text;
        line.style.color = bad ? '#DC2626' : '#047857';
    }

    function handle(box, req, partyIdAfter) {
        box.querySelectorAll('button').forEach(function (b) { b.disabled = true; });
        req.done(function (r) {
            if (r && r.status === 'SUCCESS') {
                refreshGrid();
                var next = partyIdAfter(r);
                if (next != null) { reopen360(next); return; }
                // The partner it joined is not known on this side: close the view and say what happened.
                $('.c360-card .c360-x').trigger('click');
                if (typeof global.showSaleSuccess === 'function') global.showSaleSuccess(r.message);
            } else {
                box.querySelectorAll('button').forEach(function (b) { b.disabled = false; });
                say(box, (r && r.message) || msg('ui.js.drFailed', 'Could not complete that. Try again.'), true);
            }
        }).fail(function (x) {
            box.querySelectorAll('button').forEach(function (b) { b.disabled = false; });
            say(box, x && x.status === 403 ? msg('ui.js.drNotAllowed', 'Only the owner or an admin can do this.')
                                            : msg('ui.js.drFailed', 'Could not complete that. Try again.'), true);
        });
    }

    function roleLabel(r) {
        return (r.role === 'VENDOR' ? msg('ui.js.drRoleSupplier', 'Supplier') : msg('ui.js.drRoleCustomer', 'Customer'))
             + (r.label ? ': ' + r.label : '');
    }

    /** Both roles present: each record can be given a partner of its own. */
    function unlinkButtons(box, party, biz) {
        biz.forEach(function (r) {
            var b = el('button', 'btn btn-xs btn-default', msg('ui.js.drUnlink', 'Unlink') + ' — ' + roleLabel(r));
            b.type = 'button';
            b.setAttribute('data-dr-unlink', r.role + ':' + r.localId);
            b.onclick = function () {
                global.uiConfirm({
                    title: msg('ui.js.drUnlinkTitle', 'Give this record a partner of its own?'),
                    message: msg('ui.js.drUnlinkMsg', 'It stops sharing this partner. Its sales, purchases and balances do not move. You can link it again later.'),
                    confirmText: msg('ui.js.drUnlink', 'Unlink')
                }).then(function (ok) {
                    if (!ok) return;
                    // After an unlink, show the partner that KEPT the other role — that is the one still being read.
                    handle(box, postJson('partyUnlink', { role: r.role, id: r.localId }), function () { return party.id; });
                });
            };
            box.appendChild(b);
            box.appendChild(document.createTextNode(' '));
        });
    }

    /** One role only: pick the record of the other kind that is the same business. */
    function linkPicker(box, party, have) {
        var wantSupplier = have.role === 'CUSTOMER';
        var open = el('button', 'btn btn-xs btn-primary',
            wantSupplier ? msg('ui.js.drLinkSupplier', 'Link to a supplier…') : msg('ui.js.drLinkCustomer', 'Link to a customer…'));
        open.type = 'button';
        open.setAttribute('data-dr-link-open', '1');
        box.appendChild(open);

        open.onclick = function () {
            open.disabled = true;
            var pick = el('div', 'dr-pick');
            var filter = el('input', 'form-control input-sm');
            filter.placeholder = msg('ui.js.drSearch', 'Type to filter');
            filter.setAttribute('data-dr-filter', '1');
            // A plain list, not a searchable dropdown widget: the widget's render is quadratic in its options and a
            // shop's customer list runs to thousands (see PSEL-1).
            var list = el('select', 'form-control input-sm');
            list.size = 8;
            list.setAttribute('data-dr-list', '1');
            var go = el('button', 'btn btn-sm btn-primary', msg('ui.js.drLink', 'Link'));
            go.type = 'button';
            go.setAttribute('data-dr-link', '1');
            pick.appendChild(filter); pick.appendChild(list); pick.appendChild(go);
            box.appendChild(pick);
            say(box, msg('ui.js.drLoading', 'Loading…'), false);

            var url = wantSupplier ? 'getUserVender' : 'customerOptions';
            $.get(serverContext + url, function (resp) {
                var rows = (resp && (resp.collection || resp.data || resp.object)) || [];
                var items = rows.map(function (x) {
                    var id = wantSupplier ? x.id : x.customerId;
                    var phone = wantSupplier ? (x.mobile || x.phone || '') : (x.contact || '');
                    return { id: id, text: (x.name || ('#' + id)) + (phone ? ' · ' + phone : ''), partyId: x.partyId };
                }).filter(function (x) { return x.id != null && (x.partyId == null || x.partyId !== party.id); });
                var draw = function () {
                    var q = filter.value.trim().toLowerCase();
                    list.textContent = '';
                    items.filter(function (x) { return !q || x.text.toLowerCase().indexOf(q) >= 0; })
                         .slice(0, 200)
                         .forEach(function (x) { var o = el('option', null, x.text); o.value = x.id; list.appendChild(o); });
                };
                filter.oninput = draw;
                draw();
                say(box, items.length ? '' : msg('ui.js.drNothingToLink', 'Nothing to link to yet.'), false);
                filter.focus();
            }, 'json').fail(function () { say(box, msg('ui.js.drFailed', 'Could not complete that. Try again.'), true); });

            go.onclick = function () {
                var otherId = list.value;
                if (!otherId) { say(box, wantSupplier ? msg('ui.js.drPickSupplier', 'Choose a supplier.') : msg('ui.js.drPickCustomer', 'Choose a customer.'), true); return; }
                global.uiConfirm({
                    title: msg('ui.js.drLinkTitle', 'Link these two as one partner?'),
                    message: msg('ui.js.drLinkMsg', 'Do this only when they are the same business. Their balances stay separate; nothing is netted.'),
                    confirmText: msg('ui.js.drLink', 'Link')
                }).then(function (ok) {
                    if (!ok) return;
                    var body = wantSupplier ? { customerId: have.localId, venderId: Number(otherId) }
                                            : { customerId: Number(otherId), venderId: have.localId };
                    // The supplier joins the CUSTOMER's partner. From a customer's view that is this partner; from a
                    // supplier's view it is the chosen customer's, which the lean customer list does not carry.
                    handle(box, postJson('partyLink', body), function () { return wantSupplier ? party.id : null; });
                });
            };
        };
    }

    /** The hook party-contact.js calls under the roles of the 360 view. */
    global.contact360Extras = function (party, roles, body) {
        var biz = (roles || []).filter(function (r) {
            return String(r.module || '').toLowerCase() === 'business' && (r.role === 'CUSTOMER' || r.role === 'VENDOR');
        });
        if (!biz.length) return;
        var hasC = biz.some(function (r) { return r.role === 'CUSTOMER'; });
        var hasV = biz.some(function (r) { return r.role === 'VENDOR'; });

        body.appendChild(el('p', 'c360-sec', msg('ui.js.drSection', 'Customer and supplier')));
        var box = el('div', 'dr-box');
        box.setAttribute('data-dr-box', '1');
        if (hasC && hasV) unlinkButtons(box, party, biz);
        else linkPicker(box, party, biz[0]);
        body.appendChild(box);
    };

    // Minimal layout for the picker; colours come from the 360 popup's own theme.
    $(function () {
        var css = '.dr-box{display:flex;flex-wrap:wrap;gap:6px;align-items:flex-start}'
                + '.dr-pick{display:flex;flex-direction:column;gap:6px;width:100%;margin-top:6px}'
                + '.dr-pick select{min-height:150px}'
                + '.dr-say{width:100%;font-size:12.5px;margin-top:4px}'
                + '.dr-badge{margin-left:6px;font-weight:600}';
        var s = document.createElement('style');
        s.textContent = css;
        document.head.appendChild(s);
    });
})(window, jQuery);
