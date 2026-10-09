/*
 * HMS S1 — the clinic's front desk (clinicDashboard.html). Talks to the monolith proxy /clinic/** → clinical-service.
 *
 * The flow is the phone FIRST (client decision M-01): type the number → a known number opens its patient
 * (one patient per phone, M-02) → an unknown number opens a short registration form where nothing else is
 * required. Every rule is the server's; this file only renders its answers. Text from the server is rendered
 * with escHtml / .text(), never as HTML.
 */
(function (global) {
    'use strict';

    var state = { phone: null, lookup: null, searchTimer: null, settings: [] };

    function url(p) { return serverContext + p.replace(/^\//, ''); }

    function msg(text, ok) {
        $('#clinMsg').stop(true, true).removeClass('alert-success alert-danger')
            .addClass(ok ? 'alert-success' : 'alert-danger').text(text).show();
        if (ok) $('#clinMsg').delay(3500).fadeOut(400);
    }

    /** The server's own sentence from a refusal (200 + success:false, or an error status with the envelope). */
    function reason(xhr, fallback) {
        var b = xhr && (xhr.responseJSON || (function () { try { return JSON.parse(xhr.responseText); } catch (e) { return null; } })());
        return (b && b.message) || fallback;
    }

    /** "The clinic is not switched on" is a state of the page, not a toast: say it once, at the top. */
    function moduleOff(text) {
        if (/not switched on/i.test(text || '')) {
            $('#clinModuleOff').text(text).show();
            return true;
        }
        return false;
    }

    function ageOf(dob) {
        if (!dob) return '';
        var d = new Date(dob), n = new Date();
        var a = n.getFullYear() - d.getFullYear();
        if (n.getMonth() < d.getMonth() || (n.getMonth() === d.getMonth() && n.getDate() < d.getDate())) a--;
        return a >= 0 ? a + ' y' : '';
    }

    function sexLabel(s) { return s === 'M' ? 'Male' : s === 'F' ? 'Female' : s === 'O' ? 'Other' : ''; }

    function linkBadge(p) {
        return p.customerId
            ? '<span class="clin-badge ok" title="Pharmacy customer #' + escHtml(p.customerId) + '">✓ Linked</span>'
            : '<span class="clin-badge warn">Pending</span>';
    }

    // ── sections ────────────────────────────────────────────────────────────────────────────────────────
    function show(id) {
        $('#clinContent .formDiv').hide();
        $('#' + id).show();
        if (id === 'ClinicSettingsDiv') loadSettings();
        if (id === 'ReceptionDiv') { loadPatients(); $('#clinPatPhone').trigger('focus'); }
    }

    // ── the phone first ─────────────────────────────────────────────────────────────────────────────────
    function reset() {
        state.phone = null; state.lookup = null;
        $('#clinPatFound').hide().empty();
        $('#clinPatForm').hide()[0].reset();
        $('#clinPatFamily').val('false');
        $('#clinPatError').hide().text('');
        $('#clinPatPhone').val('').trigger('focus');
    }

    function find() {
        var raw = $.trim($('#clinPatPhone').val());
        $('#clinPatError').hide().text('');
        $('#clinPatFound').hide().empty();
        $('#clinPatForm').hide();
        if (!raw) { $('#clinPatError').text('Enter the patient\'s mobile number.').show(); return; }
        $.getJSON(url('clinic/patients'), { phone: raw })
            .done(function (r) {
                if (!r || !r.success) { if (!moduleOff(r && r.message)) $('#clinPatError').text((r && r.message) || 'Could not look up the number.').show(); return; }
                var d = r.data;
                state.phone = d.phone; state.lookup = d;
                $('#clinPatPhone').val(d.phone);
                $('#clinCnicReq').toggle(!!d.cnicRequired);
                if (d.patients && d.patients.length) renderFound(d);
                else openForm(false);
            })
            .fail(function (xhr) {
                var t = reason(xhr, 'Could not look up the number.');
                if (!moduleOff(t)) $('#clinPatError').text(t).show();
            });
    }

    function patientCard(p, primary) {
        var h = '<div class="clin-patient' + (primary ? ' clin-patient--primary' : '') + '" data-patient-id="' + escHtml(p.id) + '">'
            + '<div class="clin-patient-main">'
            + '<div class="clin-patient-name">' + escHtml(p.name) + '</div>'
            + '<div class="clin-mrn" id="' + (primary ? 'clinPatMrn' : '') + '">' + escHtml(p.mrn) + '</div>'
            + '</div>'
            + '<dl class="clin-facts">'
            + '<div><dt>Phone</dt><dd>' + escHtml(p.phone) + '</dd></div>'
            + (p.cnic ? '<div><dt>CNIC</dt><dd>' + escHtml(p.cnic) + '</dd></div>' : '')
            + (p.dateOfBirth ? '<div><dt>Age</dt><dd>' + escHtml(ageOf(p.dateOfBirth)) + ' <small>(' + escHtml(p.dateOfBirth) + ')</small></dd></div>' : '')
            + (p.sex ? '<div><dt>Sex</dt><dd>' + escHtml(sexLabel(p.sex)) + '</dd></div>' : '')
            + '<div><dt>Pharmacy customer</dt><dd>' + linkBadge(p)
            + (p.customerId ? '' : ' <button type="button" class="btn btn-xs btn-default clin-link" data-id="' + escHtml(p.id) + '">Link now</button>')
            + '</dd></div>'
            + '</dl></div>';
        return h;
    }

    function renderFound(d) {
        var html = '<div class="clin-found-head">' + (d.patients.length > 1 ? 'Patients on this number' : 'Registered patient') + '</div>';
        d.patients.forEach(function (p, i) { html += patientCard(p, i === 0); });
        html += '<div class="clin-actions">'
            + '<button type="button" class="btn btn-default" onclick="Clinic.reset()">New search</button>';
        if (d.familyAllowed) {
            html += ' <button type="button" id="clinPatFamilyAdd" class="btn btn-primary" onclick="Clinic.addFamily()">'
                + '<span class="glyphicon glyphicon-plus"></span> Add family member on this number</button>';
        }
        html += '</div>';
        $('#clinPatFound').html(html).show();
    }

    function openForm(family) {
        $('#clinPatForm')[0].reset();
        $('#clinPatFamily').val(family ? 'true' : 'false');
        $('#clinFormPhone').text(state.phone + (family ? ' · family member' : ''));
        $('#clinPatForm').show();
        $('#clinPatName').trigger('focus');
    }

    function addFamily() { $('#clinPatFound').hide(); openForm(true); }

    function register() {
        var body = {
            phone: state.phone || $.trim($('#clinPatPhone').val()),
            name: $.trim($('#clinPatName').val()),
            cnic: $.trim($('#clinPatCnic').val()),
            dateOfBirth: $('#clinPatDob').val() || null,
            sex: $('input[name=clinPatSex]:checked').val() || null,
            addFamilyMember: $('#clinPatFamily').val() === 'true'
        };
        var $btn = $('#clinPatSave').prop('disabled', true);
        $.ajax({ type: 'POST', url: url('clinic/patients'), contentType: 'application/json', dataType: 'json', data: JSON.stringify(body) })
            .done(function (r) {
                if (r && r.success) {
                    msg(r.message, true);
                    $('#clinPatForm').hide();
                    state.lookup = { phone: r.data.phone, patients: [r.data], familyAllowed: state.lookup && state.lookup.familyAllowed };
                    renderFound(state.lookup);
                    loadPatients();
                } else if (r && r.data && r.data.existing) {
                    // M-02: the number is someone's already — open them instead of an error.
                    msg(r.message, false);
                    $('#clinPatForm').hide();
                    renderFound({ phone: r.data.existing.phone, patients: [r.data.existing], familyAllowed: state.lookup && state.lookup.familyAllowed });
                } else if (!moduleOff(r && r.message)) {
                    msg((r && r.message) || 'Could not register the patient.', false);
                }
            })
            .fail(function (xhr) { var t = reason(xhr, 'Could not register the patient.'); if (!moduleOff(t)) msg(t, false); })
            .always(function () { $btn.prop('disabled', false); });
    }

    function link(id, $btn) {
        $btn.prop('disabled', true);
        $.ajax({ type: 'POST', url: url('clinic/patients/' + encodeURIComponent(id) + '/link'), dataType: 'json' })
            .done(function (r) {
                msg((r && r.message) || 'Done.', !!(r && r.success && r.data && r.data.customerId));
                if (state.phone) find();
                loadPatients();
            })
            .fail(function (xhr) { msg(reason(xhr, 'Could not link the customer.'), false); })
            .always(function () { $btn.prop('disabled', false); });
    }

    // ── the list ────────────────────────────────────────────────────────────────────────────────────────
    function loadPatients() {
        var q = $.trim($('#clinPatSearch').val());
        $.getJSON(url('clinic/patients'), q ? { q: q } : {})
            .done(function (r) {
                var $b = $('#clinPatBody').empty();
                if (!r || !r.success) { moduleOff(r && r.message); $('#clinPatEmpty').hide(); return; }
                var rows = r.data || [];
                $('#clinPatEmpty').toggle(rows.length === 0 && !q);
                rows.forEach(function (p) {
                    var at = String(p.createdAt || '').replace('T', ' ').substring(0, 16);
                    $b.append('<tr data-patient-id="' + escHtml(p.id) + '">'
                        + '<td class="clin-mrn-cell">' + escHtml(p.mrn) + '</td>'
                        + '<td>' + escHtml(p.name) + '</td>'
                        + '<td>' + escHtml(p.phone) + '</td>'
                        + '<td>' + escHtml(at) + '</td>'
                        + '<td>' + linkBadge(p) + '</td></tr>');
                });
            })
            .fail(function (xhr) { moduleOff(reason(xhr, '')); });
    }

    // ── settings ────────────────────────────────────────────────────────────────────────────────────────
    function loadSettings() {
        $.getJSON(url('clinic/settings')).done(function (r) {
            var $s = $('#clinSettingsBody').empty();
            if (!r || !r.success) { moduleOff(r && r.message); return; }
            (r.data || []).forEach(function (e) {
                var id = 'clinSet_' + String(e.key).replace(/[^A-Za-z0-9]/g, '_');
                var input = e.type === 'BOOL'
                    ? '<input type="checkbox" id="' + id + '"' + (String(e.value) === 'true' ? ' checked' : '') + '/>'
                    : '<input type="text" class="form-control" id="' + id + '" value="' + escHtml(e.value == null ? '' : e.value) + '"/>';
                $s.append('<div class="clin-setting" data-key="' + escHtml(e.key) + '">'
                    + '<label for="' + id + '">' + escHtml(e.label) + '</label>' + input
                    + '<p class="clin-help">' + escHtml(e.help || '') + '</p>'
                    + '<button type="button" class="btn btn-sm btn-primary clin-setting-save">Save</button></div>');
            });
        }).fail(function (xhr) { moduleOff(reason(xhr, '')); });
    }

    function saveSetting($row) {
        var key = $row.data('key'), $in = $row.find('input');
        var value = $in.is(':checkbox') ? String($in.is(':checked')) : $.trim($in.val());
        $.ajax({ type: 'POST', url: url('clinic/settings') + '?key=' + encodeURIComponent(key) + '&value=' + encodeURIComponent(value), dataType: 'json' })
            .done(function (r) { msg((r && r.message) || 'Saved.', !!(r && r.success)); })
            .fail(function (xhr) {
                msg(xhr.status === 403 ? 'Only an owner or admin can change clinic settings.' : reason(xhr, 'Could not save the setting.'), false);
            });
    }

    // ── wiring ──────────────────────────────────────────────────────────────────────────────────────────
    $(function () {
        $('#clinPatPhone').on('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); find(); } });
        $('#clinPatPhone').on('blur', function () { if ($.trim(this.value) && $.trim(this.value) !== state.phone) find(); });
        $('#clinPatSearch').on('input', function () {
            clearTimeout(state.searchTimer);
            state.searchTimer = setTimeout(loadPatients, 250);
        });
        $(document).on('click', '.clin-link', function () { link($(this).data('id'), $(this)); });
        $(document).on('click', '.clin-setting-save', function () { saveSetting($(this).closest('.clin-setting')); });
        $(document).on('change', 'input[name=clinPatSex]', function () { $('#clinPatSex').val(this.value); });
        show('ReceptionDiv');
    });

    global.Clinic = { show: show, find: find, register: register, reset: reset, addFamily: addFamily };
})(window);
