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

    // ── Position (DR-3) — read-only ──────────────────────────────────────────────────────────────────────────────

    function money(v) {
        var n = Number(v) || 0;
        return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    /** One labelled figure. data-dr-pos / data-amount let a test read the exact value rather than formatted text. */
    function figure(table, key, label, amount, strong) {
        var tr = el('tr');
        tr.setAttribute('data-dr-pos', key);
        tr.setAttribute('data-amount', String(Number(amount) || 0));
        var th = el('td', null, label), td = el('td', 'dr-amt', money(amount));
        if (strong) { th.style.fontWeight = '700'; td.style.fontWeight = '700'; }
        tr.appendChild(th); tr.appendChild(td);
        table.appendChild(tr);
    }

    /** Each record opens its own statement. The 360 view closes first: the statement dialog sits beneath it. */
    function statementLine(box, type, line) {
        var row = el('div', 'dr-line');
        row.appendChild(document.createTextNode(
            (type === 'VENDOR' ? msg('ui.js.drRoleSupplier', 'Supplier') : msg('ui.js.drRoleCustomer', 'Customer'))
            + ': ' + (line.name || ('#' + line.id)) + ' — ' + money(line.due) + ' '));
        var b = el('button', 'btn btn-xs btn-default', msg('ui.js.drStatement', 'Statement'));
        b.type = 'button';
        b.setAttribute('data-dr-stmt', type + ':' + line.id);
        b.onclick = function () {
            $('.c360-card .c360-x').trigger('click');
            if (typeof global.openStatement === 'function') global.openStatement(type, line.id, line.name);
        };
        row.appendChild(b);
        box.appendChild(row);
    }

    // ── Set-off (DR-4) — owner/admin, both legs posted together or not at all ────────────────────────────────────

    /** One key per opened form: a double click or a retry after a timeout replays the first set-off, never a second. */
    function newKey(prefix) {
        return prefix + '-' + (global.crypto && global.crypto.randomUUID ? global.crypto.randomUUID()
            : Date.now() + '-' + Math.random().toString(36).slice(2));
    }

    function field(box, label, input) {
        var wrap = el('label', 'dr-field');
        wrap.appendChild(el('span', null, label));
        wrap.appendChild(input);
        box.appendChild(wrap);
        return input;
    }

    function pick(lines) {
        var s = el('select', 'form-control input-sm');
        lines.forEach(function (l) {
            var o = el('option', null, (l.name || ('#' + l.id)) + ' — ' + money(l.due));
            o.value = l.id;
            s.appendChild(o);
        });
        return s;
    }

    function setOffForm(host, party, pos) {
        var limit = Number(pos.setOffLimit) || 0;
        var open = el('button', 'btn btn-xs btn-primary', msg('ui.js.drSetOff', 'Set off…'));
        open.type = 'button';
        open.setAttribute('data-dr-setoff-open', '1');
        if (limit <= 0) {
            open.disabled = true;
            open.title = msg('ui.js.drNothingToSetOff', 'Nothing to set off: one side has no open invoices or bills.');
        }
        host.appendChild(open);

        open.onclick = function () {
            open.disabled = true;
            var key = newKey('setoff');
            var form = el('div', 'dr-form');
            form.setAttribute('data-dr-setoff-form', '1');
            var cust = field(form, msg('ui.js.drRoleCustomer', 'Customer'), pick(pos.customers || []));
            var vend = field(form, msg('ui.js.drRoleSupplier', 'Supplier'), pick(pos.suppliers || []));
            var amt = el('input', 'form-control input-sm');
            amt.type = 'number'; amt.min = '0.01'; amt.step = '0.01'; amt.max = String(limit); amt.value = String(limit);
            amt.setAttribute('data-dr-amount', '1');
            field(form, msg('ui.js.drAmount', 'Amount') + ' (' + msg('ui.js.drUpTo', 'up to') + ' ' + money(limit) + ')', amt);
            var reason = el('input', 'form-control input-sm');
            reason.maxLength = 255;
            reason.placeholder = msg('ui.js.drReasonHint', 'e.g. agreed with the partner by phone');
            reason.setAttribute('data-dr-reason', '1');
            field(form, msg('ui.js.drReason', 'Reason (required)'), reason);
            var refIn = el('input', 'form-control input-sm');
            refIn.maxLength = 120;
            refIn.placeholder = msg('ui.js.drReferenceHint', 'Their letter or message, if any');
            refIn.setAttribute('data-dr-reference', '1');
            field(form, msg('ui.js.drReference', 'Reference (optional)'), refIn);
            var tick = el('input');
            tick.type = 'checkbox';
            tick.setAttribute('data-dr-same', '1');
            var tickLabel = el('label', 'dr-tick');
            tickLabel.appendChild(tick);
            tickLabel.appendChild(document.createTextNode(' ' + msg('ui.js.drSameBusiness',
                'I confirm this customer and this supplier are the same business, and they agreed to this set-off.')));
            form.appendChild(tickLabel);
            form.appendChild(el('div', 'dr-note', msg('ui.js.drSetOffNote',
                'Clears what they owe us against what we owe them. No cash moves; both statements show it.')));
            var save = el('button', 'btn btn-sm btn-success', msg('ui.js.drSetOffSave', 'Record set-off'));
            save.type = 'button';
            save.setAttribute('data-dr-setoff-save', '1');
            form.appendChild(save);
            host.appendChild(form);
            amt.focus();

            save.onclick = function () {
                var a = Number(amt.value);
                if (!tick.checked) { say(form, msg('ui.js.drNeedSame', 'Tick the confirmation first.'), true); return; }
                if (!reason.value.trim()) { say(form, msg('ui.js.drNeedReason', 'Give a reason.'), true); reason.focus(); return; }
                if (!(a > 0) || a > limit) { say(form, msg('ui.js.drBadAmount', 'Enter an amount up to the limit shown.'), true); amt.focus(); return; }
                // The SAME key on every press of this form: a timeout retried here replays, it cannot post twice.
                handle(form, postJson('partySetOff', {
                    customerId: Number(cust.value), venderId: Number(vend.value), amount: amt.value,
                    reason: reason.value.trim(), reference: refIn.value.trim(), sameBusiness: true, idempotencyKey: key
                }), function () { return party.id; });
            };
        };
    }

    function setOffList(host, party) {
        var list = el('div', 'dr-setoffs');
        list.setAttribute('data-dr-setoffs', '1');
        host.appendChild(list);
        $.get(serverContext + 'partySetOffs', { partyId: party.id }, function (r) {
            var rows = (r && (r.collection || r.data)) || [];
            if (!rows.length) return;
            list.appendChild(el('p', 'c360-sec', msg('ui.js.drSetOffs', 'Set-offs')));
            rows.forEach(function (s) {
                var row = el('div', 'dr-line');
                row.setAttribute('data-dr-setoff-row', s.setOffNo);
                var reversed = s.status === 'REVERSED';
                row.appendChild(document.createTextNode(s.setOffNo + ' — ' + money(s.amount)
                    + (reversed ? ' · ' + msg('ui.js.drReversed', 'reversed') : '')));
                if (!reversed) {
                    var rev = el('button', 'btn btn-xs btn-default', msg('ui.js.drReverse', 'Reverse'));
                    rev.type = 'button';
                    rev.setAttribute('data-dr-reverse', s.setOffNo);
                    var key = newKey('setoff-rev');
                    rev.onclick = function () {
                        global.uiPromptConfirm({
                            title: msg('ui.js.drReverseTitle', 'Reverse this set-off?') + ' ' + s.setOffNo,
                            message: msg('ui.js.drReverseMsg', 'Both balances go back to what they were before it. A mirror entry is posted; nothing is deleted.'),
                            input: { label: msg('ui.js.drReason', 'Reason (required)'), maxlength: 255 },
                            confirmText: msg('ui.js.drReverse', 'Reverse'),
                            tone: 'danger'
                        }).then(function (why) {
                            if (why == null) return;
                            if (!String(why).trim()) { say(list, msg('ui.js.drNeedReason', 'Give a reason.'), true); return; }
                            handle(list, postJson('partySetOffReverse', { setOffId: s.id, reason: String(why).trim(), idempotencyKey: key }),
                                function () { return party.id; });
                        });
                    };
                    row.appendChild(rev);
                }
                list.appendChild(row);
            });
        }, 'json');
    }

    function renderPosition(host, pos, party) {
        var table = el('table', 'dr-pos');
        figure(table, 'receivable', msg('ui.js.drTheyOwe', 'They owe us'), pos.receivable);
        figure(table, 'payable', msg('ui.js.drWeOwe', 'We owe them'), pos.payable);
        if (Number(pos.storeCredit) > 0) figure(table, 'storeCredit', msg('ui.js.drStoreCredit', 'Store credit we hold for them'), pos.storeCredit);
        var net = Number(pos.netIfSetOff) || 0;
        var netLabel = net > 0 ? msg('ui.js.drNetTheyOwe', 'If set off, they would still owe us')
                     : net < 0 ? msg('ui.js.drNetWeOwe', 'If set off, we would still owe them')
                     : msg('ui.js.drNetEven', 'If set off, nothing would remain either way');
        figure(table, 'net', netLabel, Math.abs(net), true);
        host.appendChild(table);
        host.appendChild(el('div', 'dr-note', msg('ui.js.drNetNote',
            'Only if both sides agree to set off. Nothing is netted or posted here; each balance stays on its own record.')));
        (pos.customers || []).forEach(function (l) { statementLine(host, 'CUSTOMER', l); });
        (pos.suppliers || []).forEach(function (l) { statementLine(host, 'VENDOR', l); });
        if (party) {
            setOffForm(host, party, pos);
            setOffList(host, party);
        }
    }

    function loadPosition(body, party) {
        body.appendChild(el('p', 'c360-sec', msg('ui.js.drPosition', 'Position')));
        var host = el('div', 'dr-pos-host');
        host.setAttribute('data-dr-position', '1');
        host.textContent = msg('ui.js.drLoading', 'Loading…');
        body.appendChild(host);
        $.get(serverContext + 'partyPosition', { partyId: party.id }, function (r) {
            host.textContent = '';
            var pos = r && (r.object || r.data);
            if (r && r.status === 'SUCCESS' && pos) renderPosition(host, pos, party);
            else say(host, (r && r.message) || msg('ui.js.drPosFailed', 'Could not load the position.'), true);
        }, 'json').fail(function () {
            host.textContent = '';
            say(host, msg('ui.js.drPosFailed', 'Could not load the position.'), true);
        });
    }

    // ── Payment hint (DR-5) — mentions the other side, applies nothing ────────────────────────────────────────────

    /**
     * Receive Payment / Pay Vendor just opened: if the partner also has an open balance on the OTHER side, say so at
     * the top of the dialog and offer the set-off. Owner/admin only (the same rule as the 360 view) — for anyone else
     * this does nothing. Never touches the amount, the method or anything the dialog submits.
     *
     * @param role    'CUSTOMER' (receiving) or 'VENDOR' (paying)
     * @param modalId the dialog to write into
     */
    global.partyPaymentHint = function (role, id, modalId) {
        var modal = document.getElementById(modalId);
        if (!modal) return;
        $(modal).find('[data-dr-hint]').remove();   // a previous partner's note must never linger
        if (!global.canViewContact360 || !id) return;
        var params = role === 'VENDOR' ? { venderId: id } : { customerId: id };
        $.get(serverContext + 'partyPaymentHint', params, function (r) {
            var h = r && r.status === 'SUCCESS' && (r.object || r.data);
            var open = h ? Number(h.otherSideOpen) || 0 : 0;
            if (open <= 0 || !h.partyId) return;
            $(modal).find('[data-dr-hint]').remove();
            var note = el('div', 'dr-hint');
            note.setAttribute('data-dr-hint', role);
            note.setAttribute('data-amount', String(open));
            note.setAttribute('role', 'note');
            note.appendChild(document.createTextNode((role === 'VENDOR'
                ? msg('ui.js.drHintAlsoCustomer', 'This partner is also our customer and owes us')
                : msg('ui.js.drHintAlsoSupplier', 'This partner is also our supplier and we owe them'))
                + ' ' + money(open) + '. '));
            if (Number(h.setOffLimit) > 0) {
                var go = el('button', 'btn btn-xs btn-warning', msg('ui.js.drHintSetOff', 'Set off instead…'));
                go.type = 'button';
                go.setAttribute('data-dr-hint-setoff', '1');
                go.onclick = function () {
                    if (typeof global.closeModal === 'function') global.closeModal(modalId);
                    if (typeof global.openContact360 === 'function') global.openContact360(h.partyId);
                };
                note.appendChild(go);
            }
            note.appendChild(el('div', 'dr-note', msg('ui.js.drHintNote', 'Nothing is applied unless you record a set-off.')));
            var body = modal.querySelector('.crud-body') || modal;
            body.insertBefore(note, body.firstChild);
        }, 'json');
    };

    /** The hook party-contact.js calls under the roles of the 360 view. */
    global.contact360Extras = function (party, roles, body) {
        var biz = (roles || []).filter(function (r) {
            return String(r.module || '').toLowerCase() === 'business' && (r.role === 'CUSTOMER' || r.role === 'VENDOR');
        });
        if (!biz.length) return;
        var hasC = biz.some(function (r) { return r.role === 'CUSTOMER'; });
        var hasV = biz.some(function (r) { return r.role === 'VENDOR'; });

        // DR-3: the position only means something when the partner is BOTH — one side alone is its own statement.
        if (hasC && hasV) loadPosition(body, party);

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
                + '.dr-badge{margin-left:6px;font-weight:600}'
                + '.dr-pos{width:100%;border-collapse:collapse;margin:2px 0 6px}'
                + '.dr-pos td{padding:4px 6px;border-bottom:1px solid rgba(0,0,0,.08)}'
                + '.dr-pos .dr-amt{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}'
                + '.dr-note{font-size:12px;opacity:.8;margin-bottom:6px}'
                + '.dr-line{display:flex;justify-content:space-between;align-items:center;gap:8px;font-size:13px;padding:2px 0}'
                + '.dr-form{display:flex;flex-direction:column;gap:6px;margin:8px 0;padding:10px;border:1px solid rgba(0,0,0,.12);border-radius:8px}'
                + '.dr-field{display:flex;flex-direction:column;gap:2px;font-size:12.5px;font-weight:600;margin:0}'
                + '.dr-tick{font-size:12.5px;font-weight:400;display:flex;gap:6px;align-items:flex-start;margin:0}'
                + '.dr-hint{background:#FFF7E6;border:1px solid #F5C26B;color:#7A4B00;border-radius:8px;padding:8px 10px;margin-bottom:10px;font-size:13px}';
        var s = document.createElement('style');
        s.textContent = css;
        document.head.appendChild(s);
    });
})(window, jQuery);
