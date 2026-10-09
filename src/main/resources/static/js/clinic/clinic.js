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

    var state = { phone: null, lookup: null, searchTimer: null, settings: [], doctors: null, patient: null, boardTimer: null };

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
        stopBoard();
        if (id === 'ClinicSettingsDiv') loadSettings();
        if (id === 'QueueDiv') startBoard();
        if (id === 'DoctorsDiv') loadDoctorTable();
        if (id === 'ReceptionDiv') { loadPatients(); $('#clinPatPhone').trigger('focus'); }
    }

    // ── the phone first ─────────────────────────────────────────────────────────────────────────────────
    function reset() {
        state.phone = null; state.lookup = null;
        $('#clinPatFound').hide().empty();
        hideTokenPanel();
        $('#clinPatForm').hide()[0].reset();
        $('#clinPatFamily').val('false');
        $('#clinPatError').hide().text('');
        $('#clinPatPhone').val('').trigger('focus');
    }

    function find() {
        var raw = $.trim($('#clinPatPhone').val());
        $('#clinPatError').hide().text('');
        $('#clinPatFound').hide().empty();
        hideTokenPanel();
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
        selectPatient(d.patients[0]);
    }

    function openForm(family) {
        $('#clinPatForm')[0].reset();
        $('#clinPatFamily').val(family ? 'true' : 'false');
        $('#clinFormPhone').text(state.phone + (family ? ' · family member' : ''));
        $('#clinPatForm').show();
        $('#clinPatName').trigger('focus');
    }

    function addFamily() { $('#clinPatFound').hide(); hideTokenPanel(); openForm(true); }

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

    // ── S2: a token for the patient on the card ─────────────────────────────────────────────────────────
    function hideTokenPanel() { state.patient = null; $('#clinTokenPanel').hide(); $('#clinTokenSlip').hide(); }

    /** The doctors with today's numbers; `fresh` re-reads them (after a token or a doctor change). */
    function withDoctors(then, fresh) {
        if (state.doctors && !fresh) { then(state.doctors); return; }
        $.getJSON(url('clinic/doctors')).done(function (r) {
            if (!r || !r.success) { moduleOff(r && r.message); return; }
            state.doctors = r.data || [];
            then(state.doctors);
        }).fail(function (xhr) { msg(reason(xhr, 'The doctor list is not reachable right now.'), false); });
    }

    /** Client decision 02 / 02c: the patient's last doctor; else the only doctor seeing patients today; else choose. */
    function preselect(doctors, p) {
        var open = doctors.filter(function (d) { return !d.closedToday; });
        if (p && p.lastProviderId && open.some(function (d) { return d.id === p.lastProviderId; })) {
            return { id: p.lastProviderId, why: 'Last visit was with this doctor.' };
        }
        if (open.length === 1) return { id: open[0].id, why: 'The only doctor seeing patients today.' };
        return { id: null, why: '' };
    }

    function doctorOption(d) {
        var left = d.todayLimit == null ? '' : ' · ' + Math.max(0, d.todayLimit - d.issuedToday) + ' left';
        var label = d.name + (d.speciality ? ' — ' + d.speciality : '') + (d.closedToday ? ' · not today' : left)
            + (d.waitingNow ? ' · ' + d.waitingNow + ' waiting' : '');
        return '<option value="' + escHtml(d.id) + '"' + (d.closedToday ? ' disabled' : '') + '>' + escHtml(label) + '</option>';
    }

    function selectPatient(p) {
        state.patient = p;
        $('#clinPatFound .clin-patient').removeClass('clin-patient--primary');
        $('#clinPatFound .clin-patient[data-patient-id="' + p.id + '"]').addClass('clin-patient--primary');
        $('#clinTokenSlip').hide();
        withDoctors(function (doctors) {
            var $s = $('#clinDoctor').empty();
            if (!doctors.length) {
                $s.append('<option value="">No doctors yet</option>');
                $('#clinDoctorHint').html('Add a doctor first in <a href="#" onclick="Clinic.show(\'DoctorsDiv\');return false;">Doctors</a>.');
                $('#clinIssueToken').prop('disabled', true);
            } else {
                $s.append('<option value="">Choose the doctor</option>');
                doctors.forEach(function (d) { $s.append(doctorOption(d)); });
                var pick = preselect(doctors, p);
                $s.val(pick.id ? String(pick.id) : '');
                $('#clinDoctorHint').text(pick.why);
                $('#clinIssueToken').prop('disabled', false);
            }
            $('#clinTokenPanel').show();
        }, true);
    }

    function issueToken() {
        if (!state.patient) return;
        var providerId = $('#clinDoctor').val();
        if (!providerId) { msg('Choose the doctor.', false); return; }
        var $btn = $('#clinIssueToken').prop('disabled', true);
        $.ajax({ type: 'POST', url: url('clinic/tokens'), contentType: 'application/json', dataType: 'json',
                 data: JSON.stringify({ patientId: state.patient.id, providerId: Number(providerId) }) })
            .done(function (r) {
                if (r && r.success) {
                    var t = r.data;
                    $('#clinTokenNo').text(t.tokenLabel);
                    $('#clinTokenMeta').text(t.providerName + ' · ' + (t.ahead === 0 ? 'next in line' : t.ahead + ' ahead'));
                    $('#clinTokenSlip').show();
                    msg(r.message, true);
                    state.doctors = null;   // today's numbers changed
                } else { msg((r && r.message) || 'Could not issue the token.', false); }
            })
            .fail(function (xhr) { msg(reason(xhr, 'Could not issue the token.'), false); })
            .always(function () { $btn.prop('disabled', false); });
    }

    // ── S2: today's queue ───────────────────────────────────────────────────────────────────────────────
    var STATUS_WORDS = { WAITING: 'Waiting', CALLED: 'Called', IN_CONSULTATION: 'With doctor', PARKED: 'Parked',
                         COMPLETED: 'Done', CANCELLED: 'Cancelled', NO_SHOW: 'Not here' };

    function startBoard() { loadBoard(); state.boardTimer = setInterval(loadBoard, 5000); }
    function stopBoard() { if (state.boardTimer) { clearInterval(state.boardTimer); state.boardTimer = null; } }

    function loadBoard() {
        $.getJSON(url('clinic/queue')).done(function (r) {
            if (!r || !r.success) { moduleOff(r && r.message); return; }
            var rows = r.data || [], $b = $('#clinQueueBoard').empty();
            var waiting = 0, withDoctor = 0, done = 0;
            rows.forEach(function (t) {
                var s = String(t.status);
                if (s === 'WAITING') waiting++;
                else if (s === 'CALLED' || s === 'IN_CONSULTATION') withDoctor++;
                else if (s === 'COMPLETED') done++;
                var live = s === 'WAITING' || s === 'CALLED';
                $b.append('<tr data-token-id="' + escHtml(t.id) + '">'
                    + '<td class="clin-token-cell">' + escHtml(t.tokenLabel) + '</td>'
                    + '<td>' + escHtml(t.patientName || '') + '</td>'
                    + '<td class="clin-mrn-cell">' + escHtml(t.mrn || '') + '</td>'
                    + '<td>' + escHtml(t.providerName || '') + '</td>'
                    + '<td><span class="clin-status s-' + escHtml(s.toLowerCase()) + '">' + escHtml(STATUS_WORDS[s] || s) + '</span>'
                    + (s === 'WAITING' && t.ahead != null ? ' <small class="clin-help">' + (t.ahead === 0 ? 'next' : escHtml(t.ahead) + ' ahead') + '</small>' : '')
                    + (t.parkReason ? ' <small class="clin-help">' + escHtml(t.parkReason) + '</small>' : '') + '</td>'
                    + '<td class="clin-row-actions">' + (live
                        ? '<button type="button" class="btn btn-xs btn-default clin-move" data-action="noShow" data-id="' + escHtml(t.id) + '">Not here</button> '
                          + '<button type="button" class="btn btn-xs btn-danger clin-move" data-action="cancel" data-id="' + escHtml(t.id) + '">Cancel</button>'
                        : '') + '</td></tr>');
            });
            $('#clinQueueEmpty').toggle(rows.length === 0);
            $('#clinQueueSummary').html(rows.length
                ? '<span><b>' + waiting + '</b> waiting</span><span><b>' + withDoctor + '</b> with a doctor</span><span><b>' + done + '</b> done</span>'
                : '');
            $('#clinQueueUpdated').text('Updated ' + new Date().toLocaleTimeString());
        }).fail(function (xhr) { moduleOff(reason(xhr, '')); });
    }

    function moveToken(id, action) {
        var title = action === 'cancel' ? 'Cancel this token?' : 'Mark the patient as not here?';
        var go = function () {
            $.ajax({ type: 'POST', url: url('clinic/tokens/' + encodeURIComponent(id) + '/' + action),
                     contentType: 'application/json', dataType: 'json',
                     data: JSON.stringify({ reason: action === 'cancel' ? 'cancelled at reception' : 'not here when called' }) })
                .done(function (r) { msg((r && r.message) || 'Done.', !!(r && r.success)); loadBoard(); })
                .fail(function (xhr) { msg(reason(xhr, 'Could not change the token.'), false); loadBoard(); });
        };
        if (typeof uiConfirm === 'function') {
            uiConfirm({ title: title, message: 'The patient leaves today\'s line. A new token can be issued later.',
                        confirmText: action === 'cancel' ? 'Cancel token' : 'Not here', tone: 'warning' })
                .then(function (ok) { if (ok) go(); });
        } else { go(); }
    }

    // ── S2: doctors and today's limits ──────────────────────────────────────────────────────────────────
    function loadDoctorTable() {
        withDoctors(function (doctors) {
            var $b = $('#clinDoctorBody').empty();
            $('#clinDoctorEmpty').toggle(doctors.length === 0);
            doctors.forEach(function (d) {
                var today = d.closedToday ? '<span class="clin-badge warn">Not today</span>'
                    : (d.todayLimit == null ? 'No limit' : escHtml(d.todayLimit))
                      + (d.todayChanged ? ' <small class="clin-help">(today only)</small>' : '');
                $b.append('<tr data-doctor-id="' + escHtml(d.id) + '">'
                    + '<td><b>' + escHtml(d.name) + '</b>' + (d.speciality ? '<br><small class="clin-help">' + escHtml(d.speciality) + '</small>' : '') + '</td>'
                    + '<td class="clin-token-cell">' + escHtml(d.tokenPrefix || '—') + '</td>'
                    + '<td>' + (d.usualLimit == null ? 'No limit' : escHtml(d.usualLimit)) + '</td>'
                    + '<td>' + today + '</td>'
                    + '<td>' + escHtml(d.issuedToday) + '</td><td>' + escHtml(d.waitingNow) + '</td>'
                    + '<td class="clin-day-tools">'
                    + '<input type="number" min="0" class="form-control input-sm clin-day-limit" placeholder="Limit" aria-label="Today\'s limit for ' + escHtml(d.name) + '"/>'
                    + '<button type="button" class="btn btn-xs btn-primary clin-day" data-kind="limit">Set today</button> '
                    + (d.closedToday
                        ? '<button type="button" class="btn btn-xs btn-default clin-day" data-kind="reset">Open again</button>'
                        : '<button type="button" class="btn btn-xs btn-default clin-day" data-kind="closed">Not today</button>'
                          + (d.todayChanged ? ' <button type="button" class="btn btn-xs btn-default clin-day" data-kind="reset">Usual</button>' : ''))
                    + '</td></tr>');
            });
        }, true);
    }

    function setDay($row, kind) {
        var body = { providerId: Number($row.data('doctor-id')), reset: kind === 'reset', closed: kind === 'closed' };
        if (kind === 'limit') {
            var v = $.trim($row.find('.clin-day-limit').val());
            if (v === '') { msg('Type today\'s limit (0 = no limit today).', false); return; }
            body.limit = Number(v);
        }
        $.ajax({ type: 'POST', url: url('clinic/doctors/day'), contentType: 'application/json', dataType: 'json', data: JSON.stringify(body) })
            .done(function (r) { msg((r && r.message) || 'Saved.', !!(r && r.success)); loadDoctorTable(); })
            .fail(function (xhr) { msg(reason(xhr, 'Could not save the day.'), false); });
    }

    function addDoctor() {
        var noLimit = $('#clinDocNoLimit').is(':checked');
        var limit = $.trim($('#clinDocLimit').val());
        if (!noLimit && limit === '') { msg('Type the patients a day, or tick No limit.', false); return; }
        var body = { name: $.trim($('#clinDocName').val()), speciality: $.trim($('#clinDocSpeciality').val()),
                     fee: $.trim($('#clinDocFee').val()), dailyLimit: noLimit ? null : Number(limit) };
        var $btn = $('#clinDocSave').prop('disabled', true);
        $.ajax({ type: 'POST', url: url('clinic/doctors'), contentType: 'application/json', dataType: 'json', data: JSON.stringify(body) })
            .done(function (r) {
                if (r && r.success) {
                    msg(r.message, true);
                    $('#clinDoctorForm')[0].reset();
                    $('#clinDocLimit').prop('disabled', false);
                    loadDoctorTable();
                } else { msg((r && r.message) || 'Could not add the doctor.', false); }
            })
            .fail(function (xhr) { msg(reason(xhr, 'Could not add the doctor.'), false); })
            .always(function () { $btn.prop('disabled', false); });
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
        $(document).on('click', '#clinPatFound .clin-patient', function () {
            var id = Number($(this).data('patient-id'));
            var p = ((state.lookup && state.lookup.patients) || []).find(function (x) { return x.id === id; });
            if (p) selectPatient(p);
        });
        $(document).on('click', '.clin-move', function () { moveToken($(this).data('id'), $(this).data('action')); });
        $(document).on('click', '.clin-day', function () { setDay($(this).closest('tr'), $(this).data('kind')); });
        $('#clinDocNoLimit').on('change', function () { $('#clinDocLimit').prop('disabled', this.checked).val(''); });
        $(document).on('visibilitychange', function () {
            if (document.hidden) stopBoard();
            else if ($('#QueueDiv').is(':visible') && !state.boardTimer) startBoard();
        });
        show('ReceptionDiv');
    });

    global.Clinic = { show: show, find: find, register: register, reset: reset, addFamily: addFamily,
                      issueToken: issueToken, addDoctor: addDoctor };
})(window);
