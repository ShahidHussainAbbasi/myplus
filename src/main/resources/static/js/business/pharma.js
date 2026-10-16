/*
 * Pharmacy screens (slice 41) — PHARMA-only, on the single shared dashboard. REUSE-first: medicine registration is
 * the existing Item screen (relabeled "Medicine"); the medicine picker is the existing getUserItems (itemId, same
 * as the sell flow). The only net-new screen here is Prescription intake. Talks to the monolith pharma proxies
 * (/getPrescriptions, /addPrescription) → gateway → pharma-service (which stores clinical data by itemId).
 */
(function (global) {
    'use strict';

    function num(v) { var n = Number(v); return isNaN(n) ? 0 : n; }

    var rxItems = [];

    global.showPrescriptions = function () {
        $('.formDiv').hide();
        $('#PrescriptionDiv').show();
        rxItems = [];
        renderRxItems();
        loadRxItemOptions();      // REUSE the existing item list (itemId) as the medicine picker
        loadPrescriptions();
    };

    // M5 (slice 100): the medicine picker lists catalog PRODUCTS (value = productId) — the single Product master,
    // not the local business Item table. Shared by the prescription + clinical pickers.
    function loadMedicineOptions(selectSel) {
        // Every page: a medicine missing from this list cannot be dispensed at all (paged-fetch.js).
        // PERF-8: the shared cached picker; active filtering happens in SQL.
        ProductPicker.load(function (list) {
            $(selectSel).html(ProductPicker.optionsHtml(list, 'Select medicine'));
        }, function () { showFormError(t('ui.js.couldNotLoadMedicines')); });
    }
    function loadRxItemOptions() { loadMedicineOptions('#rxMedicine'); }

    global.addRxItem = function () {
        var $opt = $('#rxMedicine option:selected');
        var productId = $('#rxMedicine').val();
        if (!productId) { showFormError(t('ui.js.pickAMedicineRegisterItOnThe')); return; }
        var qty = num($('#rxQty').val());
        if (qty <= 0) { showFormError(t('ui.js.enterAQuantity')); return; }
        rxItems.push({
            productId: Number(productId), medicineName: $opt.text().trim(),
            quantity: qty, dosage: $('#rxDosage').val(), frequency: $('#rxFreq').val(), duration: $('#rxDuration').val()
        });
        $('#rxQty,#rxDosage,#rxFreq,#rxDuration').val('');
        renderRxItems();
    };

    function renderRxItems() {
        var $b = $('#rxItemsBody').empty();
        rxItems.forEach(function (it, i) {
            var tr = $('<tr>');
            tr.append($('<td>').text(it.medicineName));
            tr.append($('<td>').text(it.quantity));
            tr.append($('<td>').text(it.dosage || ''));
            tr.append($('<td>').text(it.frequency || ''));
            tr.append($('<td>').text(it.duration || ''));
            tr.append($('<td>').html("<button class='btn btn-xs btn-danger' onclick='removeRxItem(" + i + ")'>x</button>"));
            $b.append(tr);
        });
    }
    global.removeRxItem = function (i) { rxItems.splice(i, 1); renderRxItems(); };

    global.savePrescription = function () {
        if (!$('#rxPatient').val().trim()) { showFormError(t('ui.js.patientNameIsRequired')); return; }
        if (rxItems.length === 0) { showFormError(t('ui.js.addAtLeastOnePrescribedItem')); return; }
        $.ajax({
            type: 'POST', url: serverContext + 'addPrescription', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({
                patientName: $('#rxPatient').val().trim(), patientPhone: $('#rxPatientPhone').val(),
                doctorName: $('#rxDoctor').val(), doctorLicense: $('#rxLicense').val(),
                diagnosis: $('#rxDiagnosis').val(), validUntil: $('#rxValidUntil').val() || null,
                items: rxItems
            }),
            success: function (resp) {
                if (resp && resp.success) {
                    showSaleSuccess(t('ui.js.prescriptionRecorded'));
                    $('#Prescription')[0].reset(); rxItems = []; renderRxItems();
                    loadPrescriptions();
                } else { showFormError(apiMessage(resp, 'Could not save the prescription.')); }
            },
            error: function () { showFormError(t('ui.js.couldNotSaveThePrescription')); }
        });
    };

    var lastPrescriptions = [];
    // HMS S4-lite: the list is SEARCHED and PAGED (it used to draw every recent prescription at once). The search box
    // takes a clinic token of today (A-007), an MRN, a phone or a name; the server resolves token / MRN / phone to the
    // person through the clinic when it is on. lastPrescriptions keeps every row loaded so far — Dispense reads it.
    var rxQuery = { q: '', page: 0 };

    function rxRow(p) {
        var at = fmtDateTime(p.createdAt);   // H6: the viewer's zone
        var tr = $('<tr>').attr('data-rx-id', p.id);
        // Contact-360 rides in the patient cell: a pharmacy patient is often also a POS customer.
        tr.append($('<td>').text(p.patientName || '').append(contact360Button(p.partyId)));
        // HMS S3b-1: a prescription the clinic's doctor submitted says so, with the token the patient holds
        var doc = $('<td>').text(p.doctorName || '');
        if (p.source === 'DOCTOR') {
            doc.append(' ').append($('<span class="label label-info rx-from-doctor">')
                .text(tOr('ui.js.rx.fromDoctor', 'From the doctor') + (p.tokenLabel ? ' · ' + p.tokenLabel : '')));
        }
        // HC-1d: sent by a LINKED clinic (a separate business) — its name, stamped when it was sent
        if (p.fromOrgName) {
            doc.append(' ').append($('<span class="label label-primary rx-from-clinic">')
                .text(tOr('ui.js.rx.fromClinic', 'From clinic {0}', p.fromOrgName)));
        }
        tr.append(doc);
        tr.append($('<td>').text((p.items || []).length));
        tr.append($('<td>').text(p.status || ''));
        tr.append($('<td>').text(at));
        // Dispense is a normal sale that fulfils this Rx — only offer it while the script is still live.
        // EXPIRED is derived server-side from validUntil, so it appears here without any nightly job.
        var action;
        if (p.status === 'FULLY_DISPENSED') action = '<span class="text-muted">' + escHtml(tOr('ui.js.rx.stDispensed', 'dispensed')) + '</span>';
        else if (p.status === 'CANCELLED') action = '<span class="text-muted">cancelled</span>';
        else if (p.status === 'EXPIRED') action = '<span class="text-muted">expired</span>';
        else if (!canDispense()) action = '<span class="text-muted">' + escHtml(tOr('ui.js.rx.withPharmacist', 'with the pharmacist')) + '</span>';   // H3b: the pharmacist's
        else action = "<button class='btn btn-xs btn-success' onclick='dispenseFromPrescription(" + p.id + ")'>" + escHtml(tOr('ui.js.rx.dispense', 'Dispense')) + "</button>"
                   + " <button class='btn btn-xs btn-default rx-change' onclick='openRxAmend(" + p.id + ")'>" + escHtml(tOr('ui.js.rx.change', 'Change')) + "</button>"
                   + " <button class='btn btn-xs btn-default' onclick='cancelPrescription(" + p.id + ")'>" + escHtml(tOr('ui.js.rx.cancel', 'Cancel')) + "</button>";
        // every change the pharmacy made is on the record, for everyone who can see the list
        action += " <button class='btn btn-xs btn-link rx-history-link' onclick='openRxHistory(" + p.id + ")'>" + escHtml(tOr('ui.js.rx.history', 'History')) + "</button>";
        tr.append($('<td>').html(action));
        return tr;
    }

    function loadPrescriptions(append) {
        if (!append) { rxQuery.page = 0; }
        $.get(serverContext + 'searchPrescriptions', { q: rxQuery.q, page: rxQuery.page }, function (resp) {
            var data = (resp && resp.data) ? resp.data : { items: [], hasMore: false };
            var items = data.items || [];
            var $b = $('#prescriptionBody');
            if (!append) { $b.empty(); lastPrescriptions = []; }
            items.forEach(function (p) { lastPrescriptions.push(p); $b.append(rxRow(p)); });
            $('#rxLoadMoreWrap').toggle(!!data.hasMore);   // the wrapper: a .btn cannot be hidden (theme.css)
            $('#rxSearchEmpty').toggle(!append && items.length === 0 && !!rxQuery.q);
            var who = resp && resp.resolved;
            $('#rxSearchFound').toggle(!!who).text(who
                ? 'Showing ' + (who.name || '') + (who.mrn ? ' · ' + who.mrn : '') + (who.phone ? ' · ' + who.phone : '') : '');
            $('#rxSearchClearWrap').toggle(!!rxQuery.q);
        }).fail(function () { showFormError(t('ui.js.couldNotLoadPrescriptions')); });
    }
    global.searchPrescriptions = function () {
        rxQuery.q = $.trim($('#rxSearch').val() || '');
        loadPrescriptions(false);
    };
    global.clearPrescriptionSearch = function () {
        $('#rxSearch').val('');
        rxQuery.q = '';
        loadPrescriptions(false);
    };
    global.loadMorePrescriptions = function () {
        rxQuery.page += 1;
        loadPrescriptions(true);
    };
    $(document).on('keydown', '#rxSearch', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); global.searchPrescriptions(); }
    });
    global.loadPrescriptions = loadPrescriptions;

    // P6 (slice 43): start dispensing a prescription — it's a normal sale on the (relabeled) Sell screen; on
    // Complete Sale the post-sale hook records the dispense against this Rx (window.dispensingPrescriptionId).
    //
    // RX-FILL-1: the till opens with the prescribed medicines ALREADY in the cart, at what is still owed on the
    // script; the counter asks the patient and adjusts each line with + / -. A cart that already holds an
    // unrelated basket is never merged into a dispense — the counter chooses Replace, or nothing happens.
    global.dispenseFromPrescription = function (id) {
        var rx = lastPrescriptions.find(function (p) { return p.id === id; }) || {};
        var cartLines = (window.data && window.data.length) ? window.data.length : 0;
        if (cartLines > 0 && typeof uiConfirm === 'function') {
            uiConfirm({
                title: 'Replace the cart?',
                message: 'The cart already has ' + cartLines + ' line(s). Dispensing this prescription replaces them '
                    + 'with the prescribed medicines.',
                confirmText: 'Replace',
                tone: 'warning'
            }).then(function (ok) {
                if (!ok) return;
                if (typeof resetCart === 'function') resetCart();
                startDispense(id, rx);
            });
            return;
        }
        startDispense(id, rx);
    };

    function startDispense(id, rx) {
        window.dispensingPrescriptionId = id;
        window.dispensingRx = rx;
        window.dispensingFillNotes = [];
        window.dispensingFilled = false;
        $('#dispenseRxLabel').text('Rx #' + id + (rx.patientName ? ' — ' + rx.patientName : ''));
        // reuse the Sell screen. The global .dropdown change handler loads the sell table but is brittle on some
        // pages — guard it so a failure there can't abort the screen switch, then reveal #sellDiv directly (M4a).
        try { $('#sellType').val('sellDiv').trigger('change'); } catch (e) { /* global handler failed — switch anyway */ }
        $('.formDiv').hide(); $('#sellDiv').show();
        $('#dispenseBanner').show();
        rxFillNote();
        presetDispenseCustomer(rx);   // HMS S4-lite: the patient is the customer
        // P7: warn the pharmacist about controlled products / interactions before they dispense. The cart is
        // filled only once that is settled — a declined SEVERE interaction must leave nothing behind.
        var productIds = (rx.items || []).map(function (it) { return it.productId; }).filter(Boolean);
        checkSafetyForItems(productIds, function () { fillCartFromRx(rx); });
    }

    /**
     * HMS S4-lite — the sale's customer is the PATIENT, never typed by the pharmacist. The prescription carries the
     * person (partyId); the pharmacy customer for that person was made when reception registered them. Found → that
     * customer: picked in the list when the cashier can see it there, else named on the sale and sent by id
     * (window.dispensingCustomerId, read by main.js) — a pharmacist often cannot see a customer reception created,
     * and a sale that only carried the name would create a DUPLICATE customer. No customer yet → the patient's name
     * and phone, as before.
     */
    function presetDispenseCustomer(rx) {
        window.dispensingCustomerId = null;
        var byName = function () {
            if (typeof onCustomerModeChange === 'function') onCustomerModeChange('manual');
            $('#sellCN').val(rx.patientName || '');
            $('#sellCC').val(rx.patientPhone || '');
        };
        if (!rx.partyId) { byName(); return; }
        $.get(serverContext + 'customerForParty', { partyId: rx.partyId }).done(function (r) {
            if (window.dispensingRx !== rx) return;   // backed out meanwhile
            var c = r && String(r.status).toUpperCase() === 'SUCCESS' ? r.object : null;
            if (!c || c.customerId == null) { byName(); return; }
            var $dd = $('#sellCustomerDD');
            if ($dd.find('option[value="' + c.customerId + '"]').length) {
                if (typeof onCustomerModeChange === 'function') onCustomerModeChange('select');
                $dd.val(String(c.customerId));
                if (typeof refreshSearchableSelect === 'function') refreshSearchableSelect($dd);
                if (typeof onSellCustomerSelect === 'function') onSellCustomerSelect($dd[0]);
            } else {
                if (typeof onCustomerModeChange === 'function') onCustomerModeChange('manual');
                $('#sellCN').val(c.name || rx.patientName || '');
                $('#sellCC').val(c.contact || rx.patientPhone || '');
                window.dispensingCustomerId = Number(c.customerId);
            }
        }).fail(byName);
    }

    function owedOf(it) {
        return Math.max(0, (Number(it.quantity) || 0) - (Number(it.dispensedQuantity) || 0));
    }

    /** A GET that always resolves — null on failure — so one missing answer cannot stop the whole fill. */
    function getSafe(url) {
        var d = $.Deferred();
        $.get(serverContext + url).done(function (r) { d.resolve(r); }).fail(function () { d.resolve(null); });
        return d.promise();
    }

    /**
     * ⭐ RX-FILL-1 — put what is still OWED on the script into the cart, one medicine at a time.
     *
     * A script is written in TABLETS (pieces). How that becomes a cart line depends on how the shop sells it:
     *   - sellable loose  -> a LOOSE line of exactly the tablets owed (15 tablets);
     *   - packs only      -> WHOLE packs, rounded UP (15 tablets of a 10-pack = 2 packs), and said so. The dispense
     *                        record is capped server-side at the 15 prescribed.
     * Capped at SELLABLE stock (never a line the server will refuse), and every shortfall is listed on the banner.
     * Lines go in through scanAddToCart — the same path as a scan — so price, unit and the rx notice are the
     * till's own; the server still prices and allocates batches at submit.
     */
    function fillCartFromRx(rx) {
        var items = (rx.items || []).filter(function (it) { return it.productId && owedOf(it) > 0; });
        var notes = window.dispensingFillNotes = [];
        var i = 0;
        (function next() {
            if (window.dispensingPrescriptionId !== rx.id) return;   // backed out while filling
            if (i >= items.length) {
                if (!items.length) notes.push('Nothing is left to dispense on this prescription.');
                window.dispensingFilled = true;
                rxFillNote();
                return;
            }
            var it = items[i++], owed = owedOf(it);
            $.when(getSafe('getCatalogProduct?id=' + it.productId),
                   getSafe('looseInfo?productId=' + it.productId),
                   getSafe('productSellable?productId=' + it.productId)).done(function (r1, r2, r3) {
                if (window.dispensingPrescriptionId !== rx.id) return;
                var p = r1 && r1.data ? r1.data : null;
                var name = (p && p.name) || it.medicineName || ('Product #' + it.productId);
                if (!p || p.id == null) {
                    notes.push(name + ': could not be read from the catalogue — add it by hand.');
                    next(); return;
                }
                var li = (r2 && typeof apiOk === 'function' && apiOk(r2)) ? apiData(r2) : null;
                var sellable = (r3 && r3.sellable != null && !isNaN(Number(r3.sellable))) ? Number(r3.sellable) : null;
                var pack = Number(p.packSize) > 1 ? Number(p.packSize) : 1;
                var ref = { id: p.id, name: name, sellingPrice: p.sellingPrice, packSize: p.packSize,
                    description: p.description };
                if (li && li.allowLoose) {
                    var pieces = owed;
                    if (sellable != null) {
                        var capPieces = Math.floor(sellable * pack);
                        if (capPieces <= 0) { notes.push(name + ': out of stock — not added.'); next(); return; }
                        if (pieces > capPieces) {
                            notes.push(name + ': only ' + capPieces + ' in stock of ' + owed + ' left on the script.');
                            pieces = capPieces;
                        }
                    }
                    scanAddToCart(ref, pieces, 'LOOSE', li);
                } else {
                    var packs = Math.ceil(owed / pack);
                    if (sellable != null) {
                        var capPacks = Math.floor(sellable);
                        if (capPacks <= 0) { notes.push(name + ': out of stock — not added.'); next(); return; }
                        if (packs > capPacks) {
                            notes.push(name + ': only ' + capPacks + (pack > 1 ? ' pack(s)' : '') + ' in stock.');
                            packs = capPacks;
                        }
                    }
                    if (pack > 1 && packs * pack !== owed) {
                        notes.push(name + ': sold in whole packs — ' + packs + ' pack(s) = ' + (packs * pack)
                            + ' for ' + owed + ' prescribed.');
                    }
                    scanAddToCart(ref, packs, 'PACK', null);
                }
                next();
            });
        })();
    }
    global.fillCartFromRx = fillCartFromRx;

    /**
     * The banner's per-medicine line: what the script still owes against what THIS sale hands over, so the
     * counter sees the effect of every + / - before Complete Sale. Called from renderCart() on every change.
     */
    function rxFillNote() {
        var $n = $('#dispenseFillNote');
        if (!$n.length) return;
        var rx = window.dispensingRx;
        if (!window.dispensingPrescriptionId || !rx) { $n.empty().hide(); return; }
        var inCart = {};
        dispenseItemsFrom(window.data || []).forEach(function (l) {
            inCart[l.productId] = (inCart[l.productId] || 0) + (Number(l.quantity) || 0);
        });
        var $ul = $('<ul class="rx-fill">');
        (rx.items || []).forEach(function (it) {
            var owed = owedOf(it);
            if (owed <= 0) return;
            var c = inCart[Number(it.productId)] || 0;
            var txt = (it.medicineName || ('Product #' + it.productId)) + ': ' + owed + ' left on the script · '
                + c + ' in this sale';
            var $li = $('<li>').attr('data-rx-product', it.productId);
            if (c > owed) { txt += ' — ' + (c - owed) + ' more than prescribed'; $li.addClass('rx-over'); }
            else if (c === 0) { txt += ' — not in this sale'; $li.addClass('rx-under'); }
            else if (c < owed) { txt += ' — ' + (owed - c) + ' left for another day'; $li.addClass('rx-under'); }
            $ul.append($li.text(txt));
        });
        (window.dispensingFillNotes || []).forEach(function (t2) { $ul.append($('<li class="rx-warn">').text(t2)); });
        $n.empty().append($ul).show();
    }
    global.rxFillNote = rxFillNote;

    /** Park (RX-FILL-0): what a parked cart must carry so Resume picks the dispense back up. */
    global.dispenseParkInfo = function () {
        return window.dispensingPrescriptionId
            ? { prescriptionId: window.dispensingPrescriptionId, prescriptionLabel: $('#dispenseRxLabel').text() }
            : null;
    };

    /** Resume (RX-FILL-0): a parked dispense comes back AS a dispense — link, banner and the per-medicine note. */
    global.resumeDispense = function (cart) {
        if (!cart || !cart.prescriptionId) return;
        var id = Number(cart.prescriptionId);
        window.dispensingPrescriptionId = id;
        window.dispensingFillNotes = [];
        window.dispensingFilled = true;
        $('#dispenseRxLabel').text(cart.prescriptionLabel || ('Rx #' + id));
        $('#dispenseBanner').show();
        $.get(serverContext + 'getPrescription?id=' + id, function (resp) {
            if (window.dispensingPrescriptionId !== id) return;
            window.dispensingRx = (resp && resp.data) ? resp.data : null;
            rxFillNote();
        });
    };

    // `then` runs once the pharmacist may proceed: no SEVERE interaction, one acknowledged, or the check itself
    // unavailable (it informs, it does not gate the sale - see the use-case doc section 8). Never after a decline.
    function checkSafetyForItems(productIds, then) {
        var go = function () { if (typeof then === 'function') then(); };
        if (!productIds || !productIds.length) { go(); return; }
        $.ajax({
            type: 'POST', url: serverContext + 'checkSafety', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ productIds: productIds }),   // M5 (slice 100): productId-native
            success: function (resp) {
                var rep = (resp && resp.data) ? resp.data : null;
                if (!rep) { go(); return; }
                var msgs = [];
                if (rep.controlledItems && rep.controlledItems.length) msgs.push('⚠ Controlled substance(s) on this dispense.');
                var severe = [];
                (rep.interactions || []).forEach(function (i) {
                    var line = '⚠ Interaction (' + (i.severity || '') + '): ' + (i.description || 'items interact');
                    if (String(i.severity || '').toUpperCase() === 'SEVERE') severe.push(line);
                    else msgs.push(line);
                });
                if (msgs.length) showFormError(msgs.join('  '));
                // B1/E3: a SEVERE interaction must not look like "pick a medicine". It gets the shared confirm
                // dialog so the pharmacist has to actively acknowledge it before dispensing. Owner-configurable
                // (pharmacy.interaction.blockSevere); when off, a severe interaction is shown as a warning like
                // the rest. Defaults to ON — an unset flag or a failed config read must not drop a safety step.
                if (severe.length && window.pharmaBlockSevere === false) {
                    showFormError(severe.join('  '));
                    go();
                } else if (severe.length) {
                    uiConfirm({
                        title: t('ui.js.severeDrugInteraction'),
                        message: severe.join('\n') + '\n\nDispense anyway?',
                        confirmText: t('ui.js.dispenseAnyway'),
                        tone: 'danger'
                    }).then(function (ok) { if (!ok) cancelDispense(); else go(); });
                } else {
                    go();
                }
            },
            error: function () { go(); }
        });
    }
    global.checkSafetyForItems = checkSafetyForItems;

    // ── Rx notice on the sell screen (B1) ────────────────────────────────────
    // The SERVER is the gate (SagaSellService refuses a prescription-only line on a sale that declares no
    // prescription). This is only the courtesy that tells the cashier before they reach Complete Sale. Defined
    // here, not in business.js, because it is pharmacy behaviour — business.js just calls it if it exists.
    var rxFlagged = null;   // Set of productIds flagged rx-required; loaded once per page

    function loadRxFlags(then) {
        if (rxFlagged) { then(); return; }
        $.get(serverContext + 'getClinical', function (resp) {
            rxFlagged = new Set();
            ((resp && resp.data) || []).forEach(function (c) { if (c.rxRequired) rxFlagged.add(String(c.productId)); });
            then();
        }).fail(function () { rxFlagged = new Set(); then(); });   // degrade quietly — the server still enforces
    }

    global.rxNoticeIfNeeded = function (productId, name) {
        if (window.dispensingPrescriptionId) return;    // already dispensing a prescription — nothing to warn about
        loadRxFlags(function () {
            if (!rxFlagged.has(String(productId))) return;
            showFormError((name || ('Product #' + productId)) + ' is prescription-only — start this sale from the '
                + 'prescription (Dispense), or record the prescription first.');
        });
    };

    // ── Clinical & Safety (P7) ───────────────────────────────────────────────
    global.showClinical = function () {
        $('.formDiv').hide();
        $('#ClinicalDiv').show();
        loadMedicineOptions('#clItem,#clInterA,#clInterB');   // M5 (slice 100): catalog Products (productId)
        loadClinical();
    };

    function loadClinical() {
        $.get(serverContext + 'getClinical', function (resp) {
            var list = (resp && resp.data) ? resp.data : [];
            var $b = $('#clinicalBody').empty();
            $('#clinicalEmpty').toggle(list.length === 0);
            list.forEach(function (c) {
                var tr = $('<tr>');
                tr.append($('<td>').text(c.medicineName || ''));
                tr.append($('<td>').text(c.productId));
                // These are read back from the catalog master, so what's shown is what the tills actually enforce.
                tr.append($('<td>').text(c.rxRequired ? 'Yes' : ''));
                tr.append($('<td>').text(c.controlledSubstance ? 'Yes' : ''));
                $b.append(tr);
            });
        }).fail(function () { showFormError(t('ui.js.couldNotLoadClinicalFlags')); });
    }
    global.loadClinical = loadClinical;

    global.saveClinical = function () {
        var productId = $('#clItem').val();
        if (!productId) { showFormError(t('ui.js.pickAMedicine')); return; }
        $.ajax({
            type: 'POST', url: serverContext + 'saveClinical', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ productId: Number(productId), medicineName: $('#clItem option:selected').text().trim(),
                rxRequired: $('#clRx').is(':checked'), controlledSubstance: $('#clControlled').is(':checked') }),
            success: function (resp) {
                if (resp && resp.success) { showSaleSuccess(t('ui.js.flagsSaved')); $('#clRx,#clControlled').prop('checked', false); loadClinical(); }
                else showFormError(apiMessage(resp, 'Could not save flags.'));
            },
            error: function () { showFormError(t('ui.js.couldNotSaveFlags')); }
        });
    };

    global.addInteraction = function () {
        var a = $('#clInterA').val(), b = $('#clInterB').val();
        if (!a || !b || a === b) { showFormError(t('ui.js.pickTwoDifferentMedicines')); return; }
        $.ajax({
            type: 'POST', url: serverContext + 'addInteraction', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ productId1: Number(a), productId2: Number(b), severity: $('#clSeverity').val(), description: $('#clInterDesc').val() }),
            success: function (resp) {
                if (resp && resp.success) { showSaleSuccess(t('ui.js.interactionAdded')); $('#clInterDesc').val(''); }
                else showFormError(apiMessage(resp, 'Could not add interaction.'));
            },
            error: function () { showFormError(t('ui.js.couldNotAddInteraction')); }
        });
    };

    // ── Alerts & controlled register (P8) ────────────────────────────────────
    global.showPharmAlerts = function () {
        $('.formDiv').hide();
        $('#PharmAlertsDiv').show();
        loadStockAlerts();
        loadControlledRegister();
    };

    /*
     * ALERT-RETIRE (2026-10-10) — the SAME live, per-tenant answer as the header badge (STK-ALERT /stockAlertSummary):
     * expired batches still in stock, batches expiring within the business's window, low stock against the product's
     * own minimum or the business cap. It used to read inventory's stored stock_alerts, which carried no organization
     * (every tenant's rows to every caller), was written hourly as duplicates, and never held a near-expiry row at all.
     * Owner/admin only, as decided for the badge: anyone else sees why the list is empty, and still gets the register.
     */
    function loadStockAlerts() {
        var tr = function (k, f) {
            var a = Array.prototype.slice.call(arguments, 2);
            if (typeof t === 'function' && typeof tHas === 'function' && tHas(k)) return t.apply(null, [k].concat(a));
            return String(f).replace(/\{(\d+)\}/g, function (m, n) { return a[n] == null ? m : String(a[n]); });
        };
        var qty = function (v) { var n = Number(v); return isFinite(n) ? String(Math.round(n * 100) / 100) : ''; };
        var $b = $('#stockAlertsBody').empty();
        var empty = function (msg) { $('#stockAlertsEmpty').text(msg || tr('ui.js.saNone', 'Nothing needs attention.')).show(); };
        $('#stockAlertsEmpty').hide();
        $.ajax({ url: serverContext + 'stockAlertSummary', dataType: 'json', global: false }).done(function (d) {
            if (!d || d.success === false) { empty(tr('ui.js.saOwnerOnly', 'Stock alerts are shown to owners and admins.')); return; }
            var rows = [];
            (d.expiredItems || []).forEach(function (it) {
                rows.push([tr('ui.js.saTypeExpired', 'Expired'), it.name, tr('ui.js.saBatchLine', '{0} · expired {1}', qty(it.quantity), it.expiryDate || '') + (it.batchNo ? ' · ' + it.batchNo : '')]);
            });
            (d.expiringItems || []).forEach(function (it) {
                rows.push([tr('ui.js.saTypeExpiring', 'Expiring soon'), it.name, tr('ui.js.saExpiresLine', '{0} · expires {1}', qty(it.quantity), it.expiryDate || '') + (it.batchNo ? ' · ' + it.batchNo : '')]);
            });
            (d.lowItems || []).forEach(function (it) {
                rows.push([tr('ui.js.saLowHd', 'Low stock'), it.name, tr('ui.js.saLowLine', '{0} left (min {1})', qty(it.onHand), qty(it.min))]);
            });
            if (!rows.length) { empty(); return; }
            rows.forEach(function (r) {
                var $tr = $('<tr>');
                r.forEach(function (c) { $tr.append($('<td>').text(c == null ? '' : c)); });
                $b.append($tr);
            });
            // The summary lists the first few of each kind; say so when there are more.
            var more = Math.max(0, Number(d.expired || 0) - (d.expiredItems || []).length)
                + Math.max(0, Number(d.expiring || 0) - (d.expiringItems || []).length)
                + Math.max(0, Number(d.low || 0) - (d.lowItems || []).length);
            if (more > 0) $b.append($('<tr>').append($('<td colspan="3" class="text-muted">').text(tr('ui.js.saMore', '…and {0} more', more))));
        }).fail(function () { empty(tr('ui.js.saOwnerOnly', 'Stock alerts are shown to owners and admins.')); });
    }
    global.loadStockAlerts = loadStockAlerts;

    function loadControlledRegister() {
        $.get(serverContext + 'controlledRegister', function (resp) {
            var list = (resp && resp.data) ? resp.data : [];
            var $b = $('#controlledBody').empty();
            $('#controlledEmpty').toggle(list.length === 0);
            list.forEach(function (d) {
                var tr = $('<tr>');
                tr.append($('<td>').text(fmtDateTime(d.dispensedAt)));   // H6
                tr.append($('<td>').text(d.medicineName || ''));
                tr.append($('<td>').text(d.quantity));
                tr.append($('<td>').text(d.patientName || ''));
                tr.append($('<td>').text(d.invoiceNo || ''));
                $b.append(tr);
            });
        }).fail(function () { showFormError(t('ui.js.couldNotLoadTheControlledRegister')); });
    }
    global.loadControlledRegister = loadControlledRegister;

    // Withdraw a prescription (script cancelled / entered in error). Uses the shared confirm dialog — never
    // window.confirm — per the project standard.
    // ── HMS H3b: the pharmacist changes or cancels the prescription; the change is kept ───────────────────
    function canDispense() { return $('#rxCanDispense').length > 0; }
    var rxEdit = { id: null, lines: [], seen: 0, mode: null, medicines: null, byName: {} };

    function rxOf(id) { return lastPrescriptions.filter(function (x) { return String(x.id) === String(id); })[0]; }

    function loadAmendMedicines() {
        if (rxEdit.medicines || typeof ProductPicker === 'undefined') return;
        ProductPicker.load(function (list) {
            rxEdit.medicines = list || [];
            rxEdit.byName = {};
            $('#rxAmendMedicines').html(rxEdit.medicines.map(function (p) {
                var n = String(p.name || tOr('ui.js.rx.productN', 'Product #{0}', p.id));
                if (!rxEdit.byName[n.toLowerCase()]) rxEdit.byName[n.toLowerCase()] = p;
                return '<option value="' + escHtml(n) + '"></option>';
            }).join(''));
        });
    }

    function renderRxAmend() {
        var $b = $('#rxAmendBody').empty();
        rxEdit.lines.forEach(function (l, i) {
            var given = Number(l.dispensedQuantity || 0);
            var cell = function (f, mode) {
                return '<td><input type="text" class="form-control input-sm rx-amend-edit" data-i="' + i + '" data-f="' + f + '"'
                    + (mode ? ' inputmode="' + mode + '"' : '') + ' value="' + escHtml(l[f] == null ? '' : l[f]) + '" aria-label="' + escHtml(f + ' of ' + (l.medicineName || '')) + '"/></td>';
            };
            $b.append('<tr><td>' + escHtml(l.medicineName || '') + '</td>' + cell('quantity', 'numeric') + cell('dosage') + cell('frequency') + cell('duration')
                + '<td class="text-right">' + escHtml(given) + '</td><td>'
                + (given > 0 ? '<span class="text-muted">' + escHtml(tOr('ui.js.rx.stDispensed', 'dispensed')) + '</span>'
                             : '<button type="button" class="btn btn-xs btn-default rx-amend-remove" data-i="' + i + '">' + escHtml(tOr('ui.js.rx.remove', 'Remove')) + '</button>') + '</td></tr>');
        });
    }

    function openRxPanel(id, mode) {
        var p = rxOf(id); if (!p) return;
        rxEdit.id = id; rxEdit.mode = mode;
        rxEdit.lines = (p.items || []).map(function (it) { return $.extend({}, it); });
        $('#rxPanelReason').val('');
        $('#rxPanelTitle').text((mode === 'cancel' ? tOr('ui.js.rx.cancelOf', 'Cancel the prescription of {0}', p.patientName || '')
                                                   : tOr('ui.js.rx.changeOf', 'Change the prescription of {0}', p.patientName || ''))
            + (p.tokenLabel ? ' · ' + p.tokenLabel : ''));
        $('#rxAmendBox').toggle(mode === 'amend');
        $('#rxAmendSaveWrap').toggle(mode === 'amend');
        $('#rxCancelConfirmWrap').toggle(mode === 'cancel');
        $('#rxHistoryPanel').hide();
        // the screen sends how many changes it has seen: a change made meanwhile on another screen is refused
        rxEdit.seen = null;   // known once the history answers; Save waits for it
        $.getJSON(serverContext + 'prescriptionAmendments', { id: id }).done(function (r) {
            rxEdit.seen = (r && r.data && r.data.length) || 0;
        });
        if (mode === 'amend') { renderRxAmend(); loadAmendMedicines(); }
        $('#rxPanel').show();
        $('html, body').animate({ scrollTop: $('#rxPanel').offset().top - 80 }, 200);
    }
    global.openRxAmend = function (id) { openRxPanel(id, 'amend'); };
    global.closeRxPanel = function () { $('#rxPanel').hide(); rxEdit.id = null; };

    global.addRxAmendLine = function () {
        var typed = $.trim($('#rxAmendMedicine').val());
        var p = rxEdit.byName[typed.toLowerCase()];
        if (!p) { showFormError(typed ? tOr('ui.js.rx.notInList', '"{0}" is not in the pharmacy\'s list. Choose it from the suggestions.', typed) : tOr('ui.js.rx.chooseMedicine', 'Choose a medicine.')); return; }
        if (rxEdit.lines.some(function (l) { return String(l.productId) === String(p.id); })) { showFormError(tOr('ui.js.rx.alreadyOn', '{0} is on the prescription already.', p.name)); return; }
        var q = $.trim($('#rxAmendQty').val());
        if (!/^\d+$/.test(q) || +q < 1) { showFormError(tOr('ui.js.rx.qtyWhole', 'Quantity: a whole number from 1.')); return; }
        rxEdit.lines.push({ productId: p.id, medicineName: p.name, quantity: Number(q), dispensedQuantity: 0 });
        $('#rxAmendMedicine, #rxAmendQty').val('');
        renderRxAmend();
    };

    global.saveRxAmend = function () {
        if (rxEdit.seen === null) { showFormError(tOr('ui.js.rx.stillLoading', 'Still loading the changes of this prescription. Try again in a moment.')); return; }
        var reason = $.trim($('#rxPanelReason').val());
        if (!reason) { showFormError(tOr('ui.js.rx.whyChanged', 'Say why the prescription is changed — the doctor sees it.')); $('#rxPanelReason').focus(); return; }
        var items = rxEdit.lines.map(function (l) {
            return { id: l.id || null, productId: l.productId, medicineName: l.medicineName, quantity: Number(l.quantity),
                     dosage: l.dosage, frequency: l.frequency, duration: l.duration };
        });
        var $b = $('#rxAmendSave').prop('disabled', true);
        $.ajax({ type: 'POST', url: serverContext + 'amendPrescription', contentType: 'application/json', dataType: 'json',
                 data: JSON.stringify({ prescriptionId: rxEdit.id, reason: reason, amendmentsSeen: rxEdit.seen, items: items }) })
            .done(function (resp) {
                if (resp && resp.success) { showSaleSuccess(tOr('ui.js.rx.changedDot', 'Prescription changed.')); closeRxPanel(); loadPrescriptions(); }
                else showFormError(apiMessage(resp, tOr('ui.js.rx.changeFailed', 'Could not change the prescription.')));
            })
            .fail(function () { showFormError(tOr('ui.js.rx.changeFailed', 'Could not change the prescription.')); })
            .always(function () { $b.prop('disabled', false); });
    };

    global.confirmRxCancel = function () {
        var reason = $.trim($('#rxPanelReason').val());
        var p = rxOf(rxEdit.id);
        if (!reason && p && p.source === 'DOCTOR') { showFormError(tOr('ui.js.rx.whyCancelled', "Say why the doctor's prescription is cancelled — the doctor sees it.")); $('#rxPanelReason').focus(); return; }
        $.ajax({ type: 'POST', url: serverContext + 'cancelPrescription', contentType: 'application/json', dataType: 'json',
                 data: JSON.stringify({ prescriptionId: rxEdit.id, reason: reason }) })
            .done(function (resp) {
                if (resp && resp.success) { showSaleSuccess(t('ui.js.prescriptionCancelled')); closeRxPanel(); loadPrescriptions(); }
                else showFormError(apiMessage(resp, 'Could not cancel the prescription.'));
            })
            .fail(function () { showFormError(t('ui.js.couldNotCancelThePrescription')); });
    };

    global.openRxHistory = function (id) {
        var p = rxOf(id);
        $('#rxHistoryTitle').text(tOr('ui.js.rx.changesOf', 'Changes to the prescription of {0}', (p && p.patientName) || ''));
        $.getJSON(serverContext + 'prescriptionAmendments', { id: id }).done(function (r) {
            var list = (r && r.data) || [];
            var fmt = function (s) {
                if (!s) return '—';
                return [s.qty != null ? s.qty : '', s.dose, s.freq, s.days].filter(function (x) { return x !== null && x !== undefined && x !== ''; }).join(' · ');
            };
            $('#rxHistoryList').html(list.length ? list.map(function (a) {
                var at = fmtDateTime(a.amendedAt);
                var lines = (a.changes || []).map(function (c) {
                    var what = !c.before ? tOr('ui.js.rx.added', 'added') : !c.after ? tOr('ui.js.rx.removed', 'removed') : tOr('ui.js.rx.changed', 'changed');
                    return '<li><b>' + escHtml(c.medicine || '') + '</b> ' + what + ': ' + escHtml(fmt(c.before)) + ' → ' + escHtml(fmt(c.after)) + '</li>';
                }).join('');
                return '<li class="rx-history-item"><div><b>' + escHtml(a.kind === 'CANCEL' ? tOr('ui.js.rx.cancelledCap', 'Cancelled') : tOr('ui.js.rx.changedCap', 'Changed')) + '</b> · ' + escHtml(at)
                    + '</div><div class="rx-history-reason">' + escHtml(a.reason || '') + '</div>' + (lines ? '<ul>' + lines + '</ul>' : '') + '</li>';
            }).join('') : '<li class="text-muted">' + escHtml(tOr('ui.js.rx.noChanges', 'No changes. This is the prescription as written.')) + '</li>');
            $('#rxPanel').hide();
            $('#rxHistoryPanel').show();
        }).fail(function () { showFormError(tOr('ui.js.rx.historyFailed', 'Could not load the changes.')); });
    };

    $(document).on('input', '.rx-amend-edit', function () {
        var l = rxEdit.lines[+$(this).data('i')]; if (l) l[$(this).data('f')] = this.value;
    });
    $(document).on('click', '.rx-amend-remove', function () { rxEdit.lines.splice(+$(this).data('i'), 1); renderRxAmend(); });

    global.cancelPrescription = function (id) {
        // H3b: a doctor's prescription is cancelled with a reason the doctor sees — the panel asks for it
        var p = rxOf(id);
        if (p && p.source === 'DOCTOR') { openRxPanel(id, 'cancel'); return; }
        uiConfirm({
            title: t('ui.js.cancelThisPrescription'),
            message: t('ui.js.itCanNoLongerBeDispensedAnything'),
            confirmText: t('ui.js.cancelPrescription'),
            tone: 'danger'
        }).then(function (ok) {
            if (!ok) return;
            $.ajax({
                type: 'POST', url: serverContext + 'cancelPrescription', contentType: 'application/json', dataType: 'json',
                data: JSON.stringify({ prescriptionId: id }),
                success: function (resp) {
                    if (resp && resp.success) { showSaleSuccess(t('ui.js.prescriptionCancelled')); loadPrescriptions(); }
                    else showFormError(apiMessage(resp, 'Could not cancel the prescription.'));
                },
                error: function () { showFormError(t('ui.js.couldNotCancelThePrescription')); }
            });
        });
    };

    // RX-FILL-0: backing out of a dispense drops the cart WITH the link. Leaving the link behind charged the next
    // sale - possibly another customer's - to this script; leaving the lines behind sold them without it.
    global.cancelDispense = function () {
        var wasDispensing = !!window.dispensingPrescriptionId;
        window.dispensingPrescriptionId = null;
        window.dispensingCustomerId = null;
        window.dispensingRx = null;
        window.dispensingFillNotes = [];
        $('#dispenseBanner').hide();
        rxFillNote();
        if (wasDispensing && window.data && window.data.length && typeof resetCart === 'function') resetCart();
    };

    /**
     * ⭐ Cart lines → what to record against the prescription, IN TABLETS.
     *
     * <p>Extracted and exported so it can be tested directly. The first version of U8's gate posted straight
     * to {@code /dispensePrescription}, which proved the SERVER records what it is told and never that the
     * till tells it the right thing — and the defect was here, in this mapping. A gate that cannot reach the
     * code under test is asserting the artefact, not the property.
     *
     * <h3>Why tablets</h3>
     * A doctor writes a medicine, a dose and a duration, so a script's quantity is DERIVED —
     * dose x frequency x duration — and can only ever be a count of tablets. Never a count of packs.
     *
     *   sold 15 loose                  -> 15   (soldQuantity is already tablets)
     *   sold 2 packs of 10             -> 20   (the server caps at what the script allows)
     *   sold 6 of an indivisible item  -> 6    (pieces ARE the unit)
     */
    global.dispenseItemsFrom = function (cart) {
        return (cart || []).map(function (d) {
            var pieces;
            if (String(d.soldUnit || '').toUpperCase() === 'LOOSE' && Number(d.soldQuantity) > 0) {
                pieces = Number(d.soldQuantity);
            } else if (Number(d.packSizeSnapshot) > 1) {
                pieces = (Number(d.quantity) || 0) * Number(d.packSizeSnapshot);
            } else {
                pieces = Number(d.quantity) || 0;
            }
            return { productId: Number(d.productId), quantity: pieces };
        });
    };

    // Called by main.js after a successful addSell when a dispense is in progress. Records the dispense (the cart
    // items that were actually sold) against the prescription, linked to the sale invoice.
    // `lines` — the sold lines, taken by main.js BEFORE it cleared the cart (RX-DISP-1). Read from the cart only
    // when a caller passes none; after Complete Sale the cart is already empty.
    global.dispensePrescription = function (invoiceNo, lines) {
        var id = window.dispensingPrescriptionId;
        if (!id) return;
        // M5 (slice 100): the cart line keys by productId now; dispense records against the catalog Product.
        /*
         * ⚠ A DISPENSE IS RECORDED IN PIECES, BECAUSE A PRESCRIPTION IS WRITTEN IN PIECES.
         *
         * `d.quantity` on a LOOSE cart line is PACKS — 1.5 for fifteen tablets. The register's
         * `dispensedQuantity` is an `int` and the server does `min(room, line.quantity)`, so 1.5 arrived as
         * 1: a script for 15 tablets recorded ONE, left 14 apparently owed, and stayed open for a repeat
         * dispense. The stock and the money were both correct; only the clinical record was wrong — and for
         * a controlled substance that is a register understating what left the counter.
         *
         * `soldQuantity` is what the customer actually received, in the unit the script is written in.
         *
         * ⚠ PACK LINES ARE DELIBERATELY UNCHANGED HERE. Two packs of ten against a 15-tablet script still
         * record 2, not 20 — a pre-existing mismatch that predates loose selling and depends on whether this
         * shop writes scripts in tablets or in packs. Changing it needs that answer, and guessing would
         * corrupt the register in the opposite direction. Raised, not silently "fixed".
         */
        var items = Array.isArray(lines) ? lines : dispenseItemsFrom(window.data || []);
        $.ajax({
            type: 'POST', url: serverContext + 'dispensePrescription', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ prescriptionId: id, invoiceNo: invoiceNo, items: items }),
            success: function (resp) {
                if (resp && resp.success) {
                    showSaleSuccess(t('ui.js.dispenseRecordedAgainstRx') + id + '.');
                    // B4: the server records only what the prescription can account for — capped lines, items not
                    // on the script, a repeat post. The stock already left the counter, so surface every one.
                    var warnings = (resp.data && resp.data.warnings) || [];
                    if (warnings.length) showFormError(warnings.join('  '));
                    loadPrescriptions();
                } else {
                    showFormError(apiMessage(resp, 'Could not record the dispense.'));
                }
            },
            error: function () { showFormError(t('ui.js.couldNotRecordTheDispense')); },
            complete: function () {
                window.dispensingPrescriptionId = null; window.dispensingRx = null; window.dispensingFillNotes = [];
                window.dispensingCustomerId = null;
                $('#dispenseBanner').hide(); rxFillNote();
            }
        });
    };
})(window);
