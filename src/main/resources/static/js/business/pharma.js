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
        var at = String(p.createdAt || '').replace('T', ' ').substring(0, 16);
        var tr = $('<tr>').attr('data-rx-id', p.id);
        // Contact-360 rides in the patient cell: a pharmacy patient is often also a POS customer.
        tr.append($('<td>').text(p.patientName || '').append(contact360Button(p.partyId)));
        tr.append($('<td>').text(p.doctorName || ''));
        tr.append($('<td>').text((p.items || []).length));
        tr.append($('<td>').text(p.status || ''));
        tr.append($('<td>').text(at));
        // Dispense is a normal sale that fulfils this Rx — only offer it while the script is still live.
        // EXPIRED is derived server-side from validUntil, so it appears here without any nightly job.
        var action;
        if (p.status === 'FULLY_DISPENSED') action = '<span class="text-muted">dispensed</span>';
        else if (p.status === 'CANCELLED') action = '<span class="text-muted">cancelled</span>';
        else if (p.status === 'EXPIRED') action = '<span class="text-muted">expired</span>';
        else action = "<button class='btn btn-xs btn-success' onclick='dispenseFromPrescription(" + p.id + ")'>Dispense</button>"
                   + " <button class='btn btn-xs btn-default' onclick='cancelPrescription(" + p.id + ")'>Cancel</button>";
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

    function loadStockAlerts() {
        // REUSE inventory-service StockAlert system (near-expiry / low stock).
        $.get(serverContext + 'getStockAlerts', function (resp) {
            var list = (resp && resp.data) ? resp.data : [];
            var $b = $('#stockAlertsBody').empty();
            $('#stockAlertsEmpty').toggle(list.length === 0);
            list.forEach(function (a) {
                var tr = $('<tr>');
                tr.append($('<td>').text(a.alertType || a.type || ''));
                tr.append($('<td>').text(a.productId != null ? a.productId : ''));
                tr.append($('<td>').text(a.message || ''));
                tr.append($('<td>').text(String(a.createdAt || '').replace('T', ' ').substring(0, 16)));
                $b.append(tr);
            });
        }).fail(function () { $('#stockAlertsEmpty').show(); });
    }
    global.loadStockAlerts = loadStockAlerts;

    function loadControlledRegister() {
        $.get(serverContext + 'controlledRegister', function (resp) {
            var list = (resp && resp.data) ? resp.data : [];
            var $b = $('#controlledBody').empty();
            $('#controlledEmpty').toggle(list.length === 0);
            list.forEach(function (d) {
                var tr = $('<tr>');
                tr.append($('<td>').text(String(d.dispensedAt || '').replace('T', ' ').substring(0, 16)));
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
    global.cancelPrescription = function (id) {
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
