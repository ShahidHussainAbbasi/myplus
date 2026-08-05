/*
 * Document Designer (B2B Phase 3g-4) — the owner's editor for a Document Profile.
 *
 * This screen edits DATA, not markup. A profile is a list of whitelisted field keys with labels, widths and
 * alignment; the renderer binds each key to a resolver. So there is nothing here that renders a document —
 * the preview calls `DocumentRenderer.buildHtml`, the SAME function the printer calls. A preview drawn by a
 * second implementation is a preview that eventually lies about what will come out of the printer.
 *
 * Three lists govern what an owner may put on a document, and all three come from the SERVER
 * (/documentFields), not from this file. The server validates every save against the same lists, so the
 * designer can never offer a field the validator will reject, and a field added to the renderer cannot be
 * silently missing here.
 */
(function (global) {
    'use strict';

    var whitelist = null;        // { header: [...], line: [...], totals: [...] } from the server
    var columns = [];            // ordered working copy: [{key, label, width, align, on}]
    var headerGroups = [[], [], []];
    var totalRows = [];

    // ---------------------------------------------------------------- labels
    //
    // The printed default label for a field is the renderer's own i18n key, read straight off the exported
    // whitelist. Mapping field→label a second time here would be a copy that drifts the first time someone
    // renames a column.
    function specFor(kind, key) {
        var w = (global.DocumentRenderer && global.DocumentRenderer.FIELD_WHITELIST) || {};
        return (w[kind] || {})[key] || null;
    }

    function defaultLabel(kind, key) {
        var spec = specFor(kind, key);
        if (spec && spec.key) { var s = t(spec.key); if (s && s !== spec.key) return s; }
        return key;
    }

    // ---------------------------------------------------------------- sample invoice for the preview
    //
    // Shaped exactly like a /getReceipt payload, because that is what the renderer consumes. Values are
    // obviously fake so nobody mistakes a preview for a real document.
    function sampleInvoice() {
        return {
            invoiceNo: 'INV-003973',
            dated: '2026-07-16T12:44:00',
            dueDate: '2026-08-15',
            customer: {
                customerId: 1908, name: 'Sample Trade Customer', customerType: $('#dtChannel').val() === 'B2C' ? 'WALK_IN' : 'RETAILER',
                address: 'Sample address', contact: '0300-0000000', city: 'Sample City',
                cnic: '00000-0000000-0', licenseNo: 'LIC-00000', licenseExpiry: '2027-01-01'
            },
            bookedByName: 'sales@example.com',
            letterhead: {
                businessName: $('#dtName').val() || 'Your Business Name',
                addressLine1: 'Your address line', phone: '000-0000000',
                licenseNo: 'LIC-12345', licenseExpiry: '2028-12-31'
            },
            sales: [
                { itemCode: '2629', itemName: 'Sample Item A', packing: '500ML', quantity: 20, sellRate: 70,
                  totalAmount: 1400, discount: 0, taxAmount: 0, taxRate: 0, bonusQuantity: 0,
                  batches: [{ batchNo: '611168', expiryDate: '2027-05-31' }] },
                { itemCode: '2690', itemName: 'Sample Item B', packing: '1000 ml', quantity: 20, sellRate: 90,
                  totalAmount: 1800, discount: 0, taxAmount: 0, taxRate: 0, bonusQuantity: 0,
                  batches: [{ batchNo: 'A265007', expiryDate: '2027-09-30' }] },
                { itemCode: '2717', itemName: 'Sample Item C', packing: '', quantity: 10, sellRate: 90,
                  totalAmount: 900, discount: 0, taxAmount: 0, taxRate: 0, bonusQuantity: 0,
                  batches: [{ batchNo: 'C262139', expiryDate: '2027-03-31' }] }
            ],
            subTotal: 4100, taxTotal: 0, grandTotal: 4100, tradeDiscount: 0,
            dueAmount: -4100, balanceAfter: 4100,
            currencySymbol: 'Rs.', currencyWord: 'Rupees', currencyFraction: 'Paisa',
            showAmountInWords: true, showTaxBreakdown: true, showPromo: false,
            taxLabel: 'Tax'
        };
    }

    // ---------------------------------------------------------------- the profile the form describes

    function buildProfile() {
        var groups = headerGroups.map(function (g) { return g.slice(); }).filter(function (g) { return g.length; });
        var lines = columns.filter(function (c) { return c.on; }).map(function (c) {
            var col = { key: c.key };
            if (c.label) col.label = c.label;
            if (c.width) col.width = Number(c.width);
            var spec = specFor('line', c.key);
            if (spec && spec.align) col.align = spec.align;
            return col;
        });
        return {
            paper: $('#dtPaper').val(),
            numberSystem: 'indian',
            showDrCr: $('#dtShowDrCr').is(':checked'),
            header: {
                titleStyle: $('#dtTitleStyle').val(),
                showLogo: $('#dtShowLogo').is(':checked'),
                columns: groups
            },
            lines: lines,
            totals: totalRows.slice(),
            footer: { text: $('#dtFooterText').val() || '', showSignature: $('#dtShowSignature').is(':checked') }
        };
    }

    // ---------------------------------------------------------------- rendering the editor

    function renderColumns() {
        var $b = $('#tableDocColumns tbody').empty();
        columns.forEach(function (c, i) {
            var tr = $('<tr>');
            tr.append($('<td>').append($('<input type="checkbox" class="dtColOn">')
                .attr('data-key', c.key).prop('checked', !!c.on)));
            tr.append($('<td>').text(defaultLabel('line', c.key)));
            tr.append($('<td>').append($('<input type="text" class="form-control input-sm dtColLabel">')
                .attr('data-key', c.key).attr('maxlength', 40)
                .attr('placeholder', defaultLabel('line', c.key)).val(c.label || '')));
            tr.append($('<td>').append($('<input type="number" min="1" max="100" class="form-control input-sm dtColWidth">')
                .attr('data-key', c.key).val(c.width || '')));
            var up = $('<button type="button" class="btn btn-xs btn-default dtColUp">&#9650;</button>').attr('data-i', i);
            var dn = $('<button type="button" class="btn btn-xs btn-default dtColDown">&#9660;</button>').attr('data-i', i);
            if (i === 0) up.prop('disabled', true);
            if (i === columns.length - 1) dn.prop('disabled', true);
            tr.append($('<td>').append(up).append(' ').append(dn));
            $b.append(tr);
        });
    }

    function renderCheckList($host, kind, keys, selected, onToggle) {
        $host.empty();
        keys.forEach(function (key) {
            var id = 'dtf_' + kind + '_' + key;
            var lbl = $('<label style="display:block;font-weight:400">');
            var cb = $('<input type="checkbox">').attr('id', id).attr('data-key', key)
                .prop('checked', selected.indexOf(key) !== -1);
            cb.on('change', function () { onToggle(key, $(this).is(':checked')); });
            lbl.append(cb).append(' ').append(document.createTextNode(defaultLabel(kind, key)));
            $host.append($('<div class="col-sm-4">').append(lbl));
        });
    }

    function renderHeaderFields() {
        var $host = $('#docHeaderFields').empty();
        headerGroups.forEach(function (group, gi) {
            var $col = $('<div class="col-sm-4">');
            $col.append($('<div style="font-weight:700;margin-bottom:4px">')
                .text(t('ui.js.docHeaderGroup') + ' ' + (gi + 1)));
            (whitelist.header || []).forEach(function (key) {
                // A header field belongs to at most ONE group — showing it as tickable in all three would
                // let an owner print the same value three times across the top of an invoice.
                var inOther = headerGroups.some(function (g, i) { return i !== gi && g.indexOf(key) !== -1; });
                if (inOther) return;
                var lbl = $('<label style="display:block;font-weight:400">');
                var cb = $('<input type="checkbox">').attr('data-key', key)
                    .prop('checked', group.indexOf(key) !== -1);
                cb.on('change', function () {
                    if ($(this).is(':checked')) { if (group.indexOf(key) === -1) group.push(key); }
                    else { var ix = group.indexOf(key); if (ix >= 0) group.splice(ix, 1); }
                    renderHeaderFields();
                    renderDocumentPreview();
                });
                lbl.append(cb).append(' ').append(document.createTextNode(defaultLabel('header', key)));
                $col.append(lbl);
            });
            $host.append($col);
        });
    }

    function renderTotals() {
        renderCheckList($('#docTotalRows'), 'totals', whitelist.totals || [], totalRows, function (key, on) {
            var ix = totalRows.indexOf(key);
            if (on && ix === -1) totalRows.push(key);
            if (!on && ix >= 0) totalRows.splice(ix, 1);
            renderDocumentPreview();
        });
    }

    function renderDocumentPreview() {
        if (!global.DocumentRenderer) return;
        var frame = document.getElementById('docPreviewFrame');
        if (!frame) return;
        var inv = sampleInvoice();
        var html;
        try {
            html = global.DocumentRenderer.buildHtml(inv, buildProfile());
        } catch (e) {
            html = '<html><body style="font:12px sans-serif;padding:10px;color:#a00">'
                 + escHtml(t('ui.js.docPreviewFailed')) + '</body></html>';
        }
        var doc = frame.contentWindow.document;
        doc.open(); doc.write(html); doc.close();
    }

    // ---------------------------------------------------------------- form state

    function seedFromPreset(presetId) {
        var p = (global.DocumentRenderer && global.DocumentRenderer.PRESETS[presetId])
             || (global.DocumentRenderer && global.DocumentRenderer.PRESETS.TRADE_INVOICE_A4);
        applyProfile(p);
    }

    function applyProfile(p) {
        p = p || {};
        $('#dtPaper').val(p.paper || 'A4');
        $('#dtTitleStyle').val((p.header && p.header.titleStyle) || 'plain');
        $('#dtShowLogo').prop('checked', !!(p.header && p.header.showLogo));
        $('#dtShowDrCr').prop('checked', p.showDrCr === true);
        $('#dtShowSignature').prop('checked', !!(p.footer && p.footer.showSignature));
        $('#dtFooterText').val((p.footer && p.footer.text) || '');

        var chosen = {};
        (p.lines || []).forEach(function (c, i) { chosen[c.key] = { order: i, col: c }; });
        // Selected columns first, in the profile's order; everything else after, unticked and available.
        var all = (whitelist.line || []).slice();
        all.sort(function (a, b) {
            var ca = chosen[a], cb = chosen[b];
            if (ca && cb) return ca.order - cb.order;
            if (ca) return -1;
            if (cb) return 1;
            return 0;
        });
        columns = all.map(function (key) {
            var c = chosen[key];
            return { key: key, on: !!c, label: c ? (c.col.label || '') : '', width: c ? (c.col.width || '') : '' };
        });

        headerGroups = [[], [], []];
        ((p.header && p.header.columns) || []).forEach(function (g, i) {
            if (i < 3) headerGroups[i] = (g || []).filter(function (k) { return (whitelist.header || []).indexOf(k) !== -1; });
        });
        totalRows = (p.totals || []).filter(function (k) { return (whitelist.totals || []).indexOf(k) !== -1; });

        renderColumns();
        renderHeaderFields();
        renderTotals();
        renderDocumentPreview();
    }

    global.resetDocumentTemplateForm = function () {
        $('#dtId').val('');
        $('#dtName').val('');
        $('#dtIsDefault').prop('checked', false);
        $('#dtFormTitle').text(t('ui.js.docNewLayout'));
        var channel = $('#dtChannel').val() || 'B2B';
        seedFromPreset(channel === 'B2C' ? 'RETAIL_RECEIPT_80MM' : 'TRADE_INVOICE_A4');
    };

    global.editDocumentTemplate = function (id) {
        $.get(serverContext + 'documentTemplate?id=' + encodeURIComponent(id), function (resp) {
            if (!resp || resp.status !== 'SUCCESS' || !resp.object) {
                showFormError((resp && resp.message) || t('ui.js.docLoadFailed'));
                return;
            }
            var row = resp.object;
            $('#dtId').val(row.id);
            $('#dtName').val(row.name || '');
            $('#dtChannel').val(row.channel || '');
            $('#dtIsDefault').prop('checked', row.isDefault === true);
            $('#dtFormTitle').text(t('ui.js.docEditLayout'));
            var profile;
            try { profile = JSON.parse(row.profileJson); } catch (e) { profile = null; }
            applyProfile(profile);
        });
    };

    global.saveDocumentTemplate = function () {
        var name = $.trim($('#dtName').val());
        if (!name) { showFormError(t('ui.js.docNameRequired')); return; }
        var body = {
            id: $('#dtId').val() ? Number($('#dtId').val()) : null,
            name: name,
            docType: 'SALE',
            channel: $('#dtChannel').val() || null,
            isDefault: $('#dtIsDefault').is(':checked'),
            profileJson: JSON.stringify(buildProfile())
        };
        // Deliberately NOT jsonPost(): that helper is the SALE submit path — it disables #addSell while in
        // flight and its success handler prints a receipt. Reusing it here would be reuse of a name, not of
        // a behaviour.
        $.ajax({
            type: 'POST', url: serverContext + 'saveDocumentTemplate',
            contentType: 'application/json', dataType: 'json', data: JSON.stringify(body),
            success: function (resp) {
                if (!resp || resp.status !== 'SUCCESS') {
                    // The server's validator speaks the owner's language ("Unknown column 'foo'."). Show it
                    // rather than a generic failure — it names the exact thing they need to change.
                    showFormError((resp && resp.message) || t('ui.js.docSaveFailed'));
                    return;
                }
                uiAlert({ title: t('ui.js.docSaved'), message: resp.message || '' });
                global.resetDocumentTemplateForm();
                loadDocumentTemplates();
            },
            error: function () { showFormError(t('ui.js.docSaveFailed')); }
        });
    };

    global.deleteDocumentTemplate = function (id) {
        uiConfirm({
            title: t('ui.js.docDeleteConfirm'),
            message: t('ui.js.docDeleteConfirmBody'),
            confirmText: t('ui.js.delete'),
            tone: 'danger'
        }).then(function (ok) {
            if (!ok) return;
            // Form-encoded on purpose: the monolith relays this one with postForm(request params).
            $.post(serverContext + 'deleteDocumentTemplate', { id: id }, function (resp) {
                if (!resp || resp.status !== 'SUCCESS') {
                    showFormError((resp && resp.message) || t('ui.js.docDeleteFailed'));
                    return;
                }
                loadDocumentTemplates();
            });
        });
    };

    function loadDocumentTemplates() {
        $.get(serverContext + 'documentTemplates', function (resp) {
            var rows = (resp && resp.collection) || [];
            var $b = $('#tableDocumentTemplate tbody').empty();
            if (!rows.length) {
                $b.append($('<tr>').append($('<td colspan="6" class="text-center">')
                    .text(t('ui.js.docNoLayouts'))));
                return;
            }
            rows.forEach(function (r) {
                var profile = {};
                try { profile = JSON.parse(r.profileJson) || {}; } catch (e) { profile = {}; }
                var tr = $('<tr>');
                tr.append($('<td>').text(r.name || ''));
                tr.append($('<td>').text(r.channel === 'B2B' ? t('ui.js.docChannelTrade')
                    : r.channel === 'B2C' ? t('ui.js.docChannelRetail') : t('ui.js.docChannelBoth')));
                tr.append($('<td>').text(profile.paper || ''));
                tr.append($('<td>').text(String((profile.lines || []).length)));
                tr.append($('<td>').text(r.isDefault ? t('ui.js.docInUse') : ''));
                tr.append($('<td>')
                    .append($('<button type="button" class="btn btn-xs btn-primary dtEdit">')
                        .attr('data-id', r.id).text(t('ui.js.edit')))
                    .append(' ')
                    .append($('<button type="button" class="btn btn-xs btn-danger dtDelete">')
                        .attr('data-id', r.id).text(t('ui.js.delete'))));
                $b.append(tr);
            });
        });
    }
    global.loadDocumentTemplates = loadDocumentTemplates;
    global.renderDocumentPreview = renderDocumentPreview;

    global.showDocumentDesigner = function () {
        $('.formDiv').hide();
        $('#DocumentDesignerDiv').show();
        // The field lists are authoritative and come from the server. Everything the screen draws depends on
        // them, so nothing is rendered until they arrive — a designer built from a half-loaded whitelist
        // would silently offer fewer fields than the build supports.
        $.get(serverContext + 'documentFields', function (resp) {
            whitelist = (resp && resp.object) || { header: [], line: [], totals: [] };
            global.resetDocumentTemplateForm();
            loadDocumentTemplates();
        }).fail(function () { showFormError(t('ui.js.docFieldsFailed')); });
    };

    // ---------------------------------------------------------------- delegated events
    //
    // Delegated because every row in these tables is rebuilt on each render; binding per row would leak
    // handlers on a screen an owner may sit on for a while.
    $(document).on('change', '.dtColOn', function () {
        var key = $(this).attr('data-key'), on = $(this).is(':checked');
        columns.forEach(function (c) { if (c.key === key) c.on = on; });
        renderDocumentPreview();
    });
    $(document).on('input', '.dtColLabel', function () {
        var key = $(this).attr('data-key'), v = $(this).val();
        columns.forEach(function (c) { if (c.key === key) c.label = v; });
        renderDocumentPreview();
    });
    $(document).on('input', '.dtColWidth', function () {
        var key = $(this).attr('data-key'), v = $(this).val();
        columns.forEach(function (c) { if (c.key === key) c.width = v; });
        renderDocumentPreview();
    });
    $(document).on('click', '.dtColUp, .dtColDown', function () {
        var i = Number($(this).attr('data-i'));
        var j = $(this).hasClass('dtColUp') ? i - 1 : i + 1;
        if (j < 0 || j >= columns.length) return;
        var tmp = columns[i]; columns[i] = columns[j]; columns[j] = tmp;
        renderColumns();
        renderDocumentPreview();
    });
    $(document).on('click', '.dtEdit', function () { global.editDocumentTemplate($(this).attr('data-id')); });
    $(document).on('click', '.dtDelete', function () { global.deleteDocumentTemplate($(this).attr('data-id')); });
    $(document).on('change', '#dtChannel', function () {
        // Switching channel on a NEW layout re-seeds from that channel's preset; an existing layout keeps
        // what the owner designed — re-seeding would throw their work away without asking.
        if (!$('#dtId').val()) global.resetDocumentTemplateForm();
    });
})(window);
