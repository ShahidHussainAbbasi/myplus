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
        $.get(serverContext + 'catalogProducts?size=2000', function (resp) {
            var list = (resp && resp.data && resp.data.content) ? resp.data.content
                     : (Array.isArray(resp && resp.data) ? resp.data : []);
            var html = "<option value=''>Select medicine</option>";
            list.forEach(function (p) { if (p.isActive === false) return; html += "<option value='" + p.id + "'>" + escHtml(p.name || ('Product #' + p.id)) + "</option>"; });
            $(selectSel).html(html);
        }).fail(function () { showFormError(t('ui.js.couldNotLoadMedicines')); });
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
                } else { showFormError((resp && resp.message) || 'Could not save the prescription.'); }
            },
            error: function () { showFormError(t('ui.js.couldNotSaveThePrescription')); }
        });
    };

    var lastPrescriptions = [];
    function loadPrescriptions() {
        $.get(serverContext + 'getPrescriptions', function (resp) {
            lastPrescriptions = (resp && resp.data) ? resp.data : [];
            var $b = $('#prescriptionBody').empty();
            lastPrescriptions.forEach(function (p) {
                var at = String(p.createdAt || '').replace('T', ' ').substring(0, 16);
                var tr = $('<tr>');
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
                $b.append(tr);
            });
        }).fail(function () { showFormError(t('ui.js.couldNotLoadPrescriptions')); });
    }
    global.loadPrescriptions = loadPrescriptions;

    // P6 (slice 43): start dispensing a prescription — it's a normal sale on the (relabeled) Sell screen; on
    // Complete Sale the post-sale hook records the dispense against this Rx (window.dispensingPrescriptionId).
    global.dispenseFromPrescription = function (id) {
        var rx = lastPrescriptions.find(function (p) { return p.id === id; }) || {};
        window.dispensingPrescriptionId = id;
        $('#dispenseRxLabel').text('Rx #' + id + (rx.patientName ? ' — ' + rx.patientName : ''));
        // reuse the Sell screen. The global .dropdown change handler loads the sell table but is brittle on some
        // pages — guard it so a failure there can't abort the screen switch, then reveal #sellDiv directly (M4a).
        try { $('#sellType').val('sellDiv').trigger('change'); } catch (e) { /* global handler failed — switch anyway */ }
        $('.formDiv').hide(); $('#sellDiv').show();
        $('#dispenseBanner').show();
        // P7: warn the pharmacist about controlled products / interactions before they dispense.
        var productIds = (rx.items || []).map(function (it) { return it.productId; }).filter(Boolean);
        checkSafetyForItems(productIds);
    };

    function checkSafetyForItems(productIds) {
        if (!productIds || !productIds.length) return;
        $.ajax({
            type: 'POST', url: serverContext + 'checkSafety', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ productIds: productIds }),   // M5 (slice 100): productId-native
            success: function (resp) {
                var rep = (resp && resp.data) ? resp.data : null;
                if (!rep) return;
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
                } else if (severe.length) {
                    uiConfirm({
                        title: t('ui.js.severeDrugInteraction'),
                        message: severe.join('\n') + '\n\nDispense anyway?',
                        confirmText: t('ui.js.dispenseAnyway'),
                        tone: 'danger'
                    }).then(function (ok) { if (!ok) cancelDispense(); });
                }
            }
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
                else showFormError((resp && resp.message) || 'Could not save flags.');
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
                else showFormError((resp && resp.message) || 'Could not add interaction.');
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
                    else showFormError((resp && resp.message) || 'Could not cancel the prescription.');
                },
                error: function () { showFormError(t('ui.js.couldNotCancelThePrescription')); }
            });
        });
    };

    global.cancelDispense = function () {
        window.dispensingPrescriptionId = null;
        $('#dispenseBanner').hide();
    };

    // Called by main.js after a successful addSell when a dispense is in progress. Records the dispense (the cart
    // items that were actually sold) against the prescription, linked to the sale invoice.
    global.dispensePrescription = function (invoiceNo) {
        var id = window.dispensingPrescriptionId;
        if (!id) return;
        // M5 (slice 100): the cart line keys by productId now; dispense records against the catalog Product.
        var items = (window.data || []).map(function (d) { return { productId: Number(d.productId), quantity: Number(d.quantity) || 0 }; });
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
                    showFormError((resp && resp.message) || 'Could not record the dispense.');
                }
            },
            error: function () { showFormError(t('ui.js.couldNotRecordTheDispense')); },
            complete: function () { window.dispensingPrescriptionId = null; $('#dispenseBanner').hide(); }
        });
    };
})(window);
