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

    function loadRxItemOptions() {
        // getUserItems returns ready-made <option value=itemId>name</option> markup — reuse it directly.
        $.get(serverContext + 'getUserItems', function (html) {
            $('#rxMedicine').html(html);
        }).fail(function () { showFormError('Could not load medicines (items).'); });
    }

    global.addRxItem = function () {
        var $opt = $('#rxMedicine option:selected');
        var itemId = $('#rxMedicine').val();
        if (!itemId) { showFormError('Pick a medicine (register it on the Item screen first).'); return; }
        var qty = num($('#rxQty').val());
        if (qty <= 0) { showFormError('Enter a quantity.'); return; }
        rxItems.push({
            itemId: Number(itemId), medicineName: $opt.text().trim(),
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
                tr.append($('<td>').text(p.patientName || ''));
                tr.append($('<td>').text(p.doctorName || ''));
                tr.append($('<td>').text((p.items || []).length));
                tr.append($('<td>').text(p.status || ''));
                tr.append($('<td>').text(at));
                // Dispense is a normal sale that fulfils this Rx — only offer it while not fully dispensed.
                var action = (p.status === 'FULLY_DISPENSED')
                    ? '<span class="text-muted">dispensed</span>'
                    : "<button class='btn btn-xs btn-success' onclick='dispenseFromPrescription(" + p.id + ")'>Dispense</button>";
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
        $('#sellType').val('sellDiv').trigger('change');   // reuse the Sell screen
        $('#dispenseBanner').show();
        // P7: warn the pharmacist about controlled items / interactions before they dispense.
        var itemIds = (rx.items || []).map(function (it) { return it.itemId; }).filter(Boolean);
        checkSafetyForItems(itemIds);
    };

    function checkSafetyForItems(itemIds) {
        if (!itemIds || !itemIds.length) return;
        $.ajax({
            type: 'POST', url: serverContext + 'checkSafety', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ itemIds: itemIds }),
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
        $.get(serverContext + 'getUserItems', function (html) {
            $('#clItem,#clInterA,#clInterB').html(html);
        });
        loadClinical();
    };

    function loadClinical() {
        $.get(serverContext + 'getClinical', function (resp) {
            var list = (resp && resp.data) ? resp.data : [];
            var $b = $('#clinicalBody').empty();
            list.forEach(function (c) {
                var tr = $('<tr>');
                tr.append($('<td>').text(c.medicineName || ''));
                tr.append($('<td>').text(c.itemId));
                tr.append($('<td>').text(c.rxRequired ? 'Yes' : ''));
                tr.append($('<td>').text(c.controlledSubstance ? 'Yes' : ''));
                $b.append(tr);
            });
        }).fail(function () { showFormError('Could not load clinical flags.'); });
    }
    global.loadClinical = loadClinical;

    global.saveClinical = function () {
        var itemId = $('#clItem').val();
        if (!itemId) { showFormError('Pick a medicine (item).'); return; }
        $.ajax({
            type: 'POST', url: serverContext + 'saveClinical', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ itemId: Number(itemId), medicineName: $('#clItem option:selected').text().trim(),
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
            data: JSON.stringify({ itemId1: Number(a), itemId2: Number(b), severity: $('#clSeverity').val(), description: $('#clInterDesc').val() }),
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

    global.cancelDispense = function () {
        window.dispensingPrescriptionId = null;
        $('#dispenseBanner').hide();
    };

    // Called by main.js after a successful addSell when a dispense is in progress. Records the dispense (the cart
    // items that were actually sold) against the prescription, linked to the sale invoice.
    global.dispensePrescription = function (invoiceNo) {
        var id = window.dispensingPrescriptionId;
        if (!id) return;
        var items = (window.data || []).map(function (d) { return { itemId: Number(d.itemId), quantity: Number(d.quantity) || 0 }; });
        $.ajax({
            type: 'POST', url: serverContext + 'dispensePrescription', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ prescriptionId: id, invoiceNo: invoiceNo, items: items }),
            success: function (resp) {
                if (resp && resp.success) showSaleSuccess('Dispense recorded against Rx #' + id + '.');
            },
            complete: function () { window.dispensingPrescriptionId = null; $('#dispenseBanner').hide(); }
        });
    };
})(window);
