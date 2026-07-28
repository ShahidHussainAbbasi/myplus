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
        }).fail(function () { showFormError('Could not load medicines.'); });
    }
    function loadRxItemOptions() { loadMedicineOptions('#rxMedicine'); }

    global.addRxItem = function () {
        var $opt = $('#rxMedicine option:selected');
        var productId = $('#rxMedicine').val();
        if (!productId) { showFormError('Pick a medicine (register it on the Product screen first).'); return; }
        var qty = num($('#rxQty').val());
        if (qty <= 0) { showFormError('Enter a quantity.'); return; }
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
        if (!$('#rxPatient').val().trim()) { showFormError('Patient name is required.'); return; }
        if (rxItems.length === 0) { showFormError('Add at least one prescribed item.'); return; }
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
                    showSaleSuccess('Prescription recorded.');
                    $('#Prescription')[0].reset(); rxItems = []; renderRxItems();
                    loadPrescriptions();
                } else { showFormError((resp && resp.message) || 'Could not save the prescription.'); }
            },
            error: function () { showFormError('Could not save the prescription.'); }
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
        }).fail(function () { showFormError('Could not load prescriptions.'); });
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
                (rep.interactions || []).forEach(function (i) {
                    msgs.push('⚠ Interaction (' + (i.severity || '') + '): ' + (i.description || 'items interact'));
                });
                if (msgs.length) showFormError(msgs.join('  '));
            }
        });
    }
    global.checkSafetyForItems = checkSafetyForItems;

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
            list.forEach(function (c) {
                var tr = $('<tr>');
                tr.append($('<td>').text(c.medicineName || ''));
                tr.append($('<td>').text(c.productId));
                tr.append($('<td>').text(c.rxRequired ? 'Yes' : ''));
                tr.append($('<td>').text(c.controlledSubstance ? 'Yes' : ''));
                $b.append(tr);
            });
        }).fail(function () { showFormError('Could not load clinical flags.'); });
    }
    global.loadClinical = loadClinical;

    global.saveClinical = function () {
        var productId = $('#clItem').val();
        if (!productId) { showFormError('Pick a medicine.'); return; }
        $.ajax({
            type: 'POST', url: serverContext + 'saveClinical', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ productId: Number(productId), medicineName: $('#clItem option:selected').text().trim(),
                rxRequired: $('#clRx').is(':checked'), controlledSubstance: $('#clControlled').is(':checked') }),
            success: function (resp) {
                if (resp && resp.success) { showSaleSuccess('Flags saved.'); $('#clRx,#clControlled').prop('checked', false); loadClinical(); }
                else showFormError((resp && resp.message) || 'Could not save flags.');
            },
            error: function () { showFormError('Could not save flags.'); }
        });
    };

    global.addInteraction = function () {
        var a = $('#clInterA').val(), b = $('#clInterB').val();
        if (!a || !b || a === b) { showFormError('Pick two different medicines.'); return; }
        $.ajax({
            type: 'POST', url: serverContext + 'addInteraction', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ productId1: Number(a), productId2: Number(b), severity: $('#clSeverity').val(), description: $('#clInterDesc').val() }),
            success: function (resp) {
                if (resp && resp.success) { showSaleSuccess('Interaction added.'); $('#clInterDesc').val(''); }
                else showFormError((resp && resp.message) || 'Could not add interaction.');
            },
            error: function () { showFormError('Could not add interaction.'); }
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
        }).fail(function () { showFormError('Could not load the controlled register.'); });
    }
    global.loadControlledRegister = loadControlledRegister;

    // Withdraw a prescription (script cancelled / entered in error). Uses the shared confirm dialog — never
    // window.confirm — per the project standard.
    global.cancelPrescription = function (id) {
        uiConfirm({
            title: 'Cancel this prescription?',
            message: 'It can no longer be dispensed. Anything already dispensed stays on the record.',
            confirmText: 'Cancel prescription',
            tone: 'danger'
        }).then(function (ok) {
            if (!ok) return;
            $.ajax({
                type: 'POST', url: serverContext + 'cancelPrescription', contentType: 'application/json', dataType: 'json',
                data: JSON.stringify({ prescriptionId: id }),
                success: function (resp) {
                    if (resp && resp.success) { showSaleSuccess('Prescription cancelled.'); loadPrescriptions(); }
                    else showFormError((resp && resp.message) || 'Could not cancel the prescription.');
                },
                error: function () { showFormError('Could not cancel the prescription.'); }
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
                    showSaleSuccess('Dispense recorded against Rx #' + id + '.');
                    // B4: the server records only what the prescription can account for — capped lines, items not
                    // on the script, a repeat post. The stock already left the counter, so surface every one.
                    var warnings = (resp.data && resp.data.warnings) || [];
                    if (warnings.length) showFormError(warnings.join('  '));
                    loadPrescriptions();
                } else {
                    showFormError((resp && resp.message) || 'Could not record the dispense.');
                }
            },
            error: function () { showFormError('Could not record the dispense.'); },
            complete: function () { window.dispensingPrescriptionId = null; $('#dispenseBanner').hide(); }
        });
    };
})(window);
