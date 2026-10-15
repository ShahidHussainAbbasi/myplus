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

    var state = { phone: null, lookup: null, searchTimer: null, settings: [], doctors: null, patient: null, boardTimer: null,
                  relink: null };   // RELINK: { tokenId, label, patientId, providerId, providerName } while moving a patient

    function url(p) { return serverContext + p.replace(/^\//, ''); }

    /** H5b — the clinic's own words: i18n.js tOr() under the ui.js.clinic. prefix (English given in place). */
    function L(key, english) {
        var args = Array.prototype.slice.call(arguments);
        args[0] = 'ui.js.clinic.' + key;
        return typeof global.tOr === 'function' ? global.tOr.apply(null, args)
            : String(english).replace(/\{(\d+)\}/g, function (m, i) { var v = args[Number(i) + 2]; return v == null ? m : String(v); });
    }

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
        return a >= 0 ? L('ageYears', '{0} y', a) : '';
    }

    function sexLabel(s) { return s === 'M' ? L('male', 'Male') : s === 'F' ? L('female', 'Female') : s === 'O' ? L('other', 'Other') : ''; }

    function linkBadge(p) {
        return p.customerId
            ? '<span class="clin-badge ok" title="' + escHtml(L('customerNo', 'Pharmacy customer #{0}', p.customerId)) + '">✓ ' + escHtml(L('linked', 'Linked')) + '</span>'
            : '<span class="clin-badge warn">' + escHtml(L('pending', 'Pending')) + '</span>';
    }

    // ── sections ────────────────────────────────────────────────────────────────────────────────────────
    function show(id) {
        $('#clinContent .formDiv').hide();
        $('#' + id).show();
        stopBoard();
        stopMyQueue();
        if (id === 'ClinicSettingsDiv') loadSettings();
        if (id === 'MyQueueDiv') startMyQueue();
        if (id === 'QueueDiv') startBoard();
        if (id === 'DoctorsDiv') loadDoctorTable();
        if (id === 'ReceptionDiv') { loadPatients(); $('#clinPatPhone').trigger('focus'); }
        if (id === 'TeamDiv' && typeof global.showTeam === 'function') global.showTeam();   // HC-1b: /js/common/team.js
        if (id === 'PharmacyLinkDiv' && typeof global.showPharmacyLink === 'function') global.showPharmacyLink();   // HC-1c
    }

    // ── the phone first ─────────────────────────────────────────────────────────────────────────────────
    function reset() {
        endRelink();
        state.phone = null; state.lookup = null;
        $('#clinPatFound').hide().empty();
        hideTokenPanel();
        $('#clinPatForm').hide()[0].reset();
        $('#clinPatFamily').val('false');
        $('#clinPatError').hide().text('');
        $('#clinPatPhone').val('').trigger('focus');
    }

    function find(keepRelink) {
        if (!keepRelink) endRelink();
        var raw = $.trim($('#clinPatPhone').val());
        $('#clinPatError').hide().text('');
        $('#clinPatFound').hide().empty();
        hideTokenPanel();
        $('#clinPatForm').hide();
        if (!raw) { $('#clinPatError').text(L('enterMobile', "Enter the patient's mobile number.")).show(); return; }
        $.getJSON(url('clinic/patients'), { phone: raw })
            .done(function (r) {
                if (!r || !r.success) { if (!moduleOff(r && r.message)) $('#clinPatError').text((r && r.message) || L('lookupFailed', 'Could not look up the number.')).show(); return; }
                var d = r.data;
                state.phone = d.phone; state.lookup = d;
                $('#clinPatPhone').val(d.phone);
                $('#clinCnicReq').toggle(!!d.cnicRequired);
                if (d.patients && d.patients.length) {
                    renderFound(d);
                    // RELINK: the patient being moved opens straight away, as if the desk had clicked their card
                    var rp = state.relink && d.patients.find(function (x) { return x.id === state.relink.patientId; });
                    if (rp) selectPatient(rp);
                } else openForm(false);
            })
            .fail(function (xhr) {
                var t = reason(xhr, L('lookupFailed', 'Could not look up the number.'));
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
            + '<div><dt>' + escHtml(L('phone', 'Phone')) + '</dt><dd>' + escHtml(p.phone) + '</dd></div>'
            + (p.cnic ? '<div><dt>' + escHtml(L('cnic', 'CNIC')) + '</dt><dd>' + escHtml(p.cnic) + '</dd></div>' : '')
            + (p.dateOfBirth ? '<div><dt>' + escHtml(L('age', 'Age')) + '</dt><dd>' + escHtml(ageOf(p.dateOfBirth)) + ' <small>(' + escHtml(p.dateOfBirth) + ')</small></dd></div>' : '')
            + (p.sex ? '<div><dt>' + escHtml(L('sex', 'Sex')) + '</dt><dd>' + escHtml(sexLabel(p.sex)) + '</dd></div>' : '')
            + '<div><dt>' + escHtml(L('pharmacyCustomer', 'Pharmacy customer')) + '</dt><dd>' + linkBadge(p)
            + (p.customerId ? '' : ' <button type="button" class="btn btn-xs btn-default clin-link" data-id="' + escHtml(p.id) + '">' + escHtml(L('linkNow', 'Link now')) + '</button>')
            + '</dd></div>'
            + '</dl></div>';
        return h;
    }

    function renderFound(d) {
        var html = '<div class="clin-found-head">' + escHtml(d.patients.length > 1 ? L('patientsOnNumber', 'Patients on this number') : L('registeredPatient', 'Registered patient')) + '</div>';
        d.patients.forEach(function (p, i) { html += patientCard(p, i === 0); });
        html += '<div class="clin-actions">'
            + '<button type="button" class="btn btn-default" onclick="Clinic.reset()">' + escHtml(L('newSearch', 'New search')) + '</button>';
        if (d.familyAllowed) {
            html += ' <button type="button" id="clinPatFamilyAdd" class="btn btn-primary" onclick="Clinic.addFamily()">'
                + '<span class="glyphicon glyphicon-plus"></span> ' + escHtml(L('addFamily', 'Add family member on this number')) + '</button>';
        }
        html += '</div>';
        $('#clinPatFound').html(html).show();
        selectPatient(d.patients[0]);
    }

    function openForm(family) {
        $('#clinPatForm')[0].reset();
        $('#clinPatFamily').val(family ? 'true' : 'false');
        $('#clinFormPhone').text(state.phone + (family ? ' · ' + L('familyMember', 'family member') : ''));
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
                    msg((r && r.message) || L('registerFailed', 'Could not register the patient.'), false);
                }
            })
            .fail(function (xhr) { var t = reason(xhr, L('registerFailed', 'Could not register the patient.')); if (!moduleOff(t)) msg(t, false); })
            .always(function () { $btn.prop('disabled', false); });
    }

    function link(id, $btn) {
        $btn.prop('disabled', true);
        $.ajax({ type: 'POST', url: url('clinic/patients/' + encodeURIComponent(id) + '/link'), dataType: 'json' })
            .done(function (r) {
                msg((r && r.message) || L('done', 'Done.'), !!(r && r.success && r.data && r.data.customerId));
                if (state.phone) find();
                loadPatients();
            })
            .fail(function (xhr) { msg(reason(xhr, L('linkCustomerFailed', 'Could not link the customer.')), false); })
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
                    var at = fmtDateTime(p.createdAt);   // H6: the viewer's zone
                    $b.append('<tr data-patient-id="' + escHtml(p.id) + '">'
                        + '<td class="clin-mrn-cell">' + escHtml(p.mrn) + '</td>'
                        + '<td>' + escHtml(p.name) + '</td>'
                        + '<td>' + escHtml(p.phone) + '</td>'
                        + '<td>' + escHtml(at) + '</td>'
                        + '<td>' + linkBadge(p) + '</td>'
                        + '<td class="clin-row-actions"><button type="button" class="btn btn-xs btn-default clin-pat-edit-btn" data-id="' + escHtml(p.id) + '">'
                        + escHtml(L('edit', 'Edit')) + '</button></td></tr>');
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
                    + '<button type="button" class="btn btn-sm btn-primary clin-setting-save">' + escHtml(L('save', 'Save')) + '</button></div>');
            });
        }).fail(function (xhr) { moduleOff(reason(xhr, '')); });
    }

    function saveSetting($row) {
        var key = $row.data('key'), $in = $row.find('input');
        var value = $in.is(':checkbox') ? String($in.is(':checked')) : $.trim($in.val());
        $.ajax({ type: 'POST', url: url('clinic/settings') + '?key=' + encodeURIComponent(key) + '&value=' + encodeURIComponent(value), dataType: 'json' })
            .done(function (r) { msg((r && r.message) || L('saved', 'Saved.'), !!(r && r.success)); })
            .fail(function (xhr) {
                msg(xhr.status === 403 ? L('settingsOwnerOnly', 'Only an owner or admin can change clinic settings.') : reason(xhr, L('settingFailed', 'Could not save the setting.')), false);
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
        }).fail(function (xhr) { msg(reason(xhr, L('doctorsUnreachable', 'The doctor list is not reachable right now.')), false); });
    }

    /** Client decision 02 / 02c: the patient's last doctor; else the only doctor seeing patients today; else choose. */
    function preselect(doctors, p) {
        var open = doctors.filter(function (d) { return !d.closedToday; });
        if (p && p.lastProviderId && open.some(function (d) { return d.id === p.lastProviderId; })) {
            return { id: p.lastProviderId, why: L('lastDoctor', 'Last visit was with this doctor.') };
        }
        if (open.length === 1) return { id: open[0].id, why: L('onlyDoctor', 'The only doctor seeing patients today.') };
        return { id: null, why: '' };
    }

    function doctorOption(d) {
        var left = d.todayLimit == null ? '' : ' · ' + L('left', '{0} left', Math.max(0, d.todayLimit - d.issuedToday));
        var label = d.name + (d.speciality ? ' — ' + d.speciality : '') + (d.closedToday ? ' · ' + L('notTodayLower', 'not today') : left)
            + (d.waitingNow ? ' · ' + L('waitingCount', '{0} waiting', d.waitingNow) : '');
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
                $s.append('<option value="">' + escHtml(L('noDoctorsYet', 'No doctors yet')) + '</option>');
                $('#clinDoctorHint').html(escHtml(L('addDoctorFirst', 'Add a doctor first in')) + ' <a href="#" onclick="Clinic.show(\'DoctorsDiv\');return false;">'
                    + escHtml(L('doctors', 'Doctors')) + '</a>.');
                $('#clinIssueToken').prop('disabled', true);
            } else {
                $s.append('<option value="">' + escHtml(L('chooseDoctorOption', 'Choose the doctor')) + '</option>');
                doctors.forEach(function (d) { $s.append(doctorOption(d)); });
                if (state.relink) {
                    var $cur = $s.find('option[value="' + state.relink.providerId + '"]');
                    $cur.prop('disabled', true).text($cur.text() + ' — ' + L('currentDoctor', 'current'));
                    $s.val('');
                    $('#clinDoctorHint').text(L('relinkChoose', 'Choose the doctor {0} goes to.', state.relink.label));
                } else {
                    var pick = preselect(doctors, p);
                    $s.val(pick.id ? String(pick.id) : '');
                    $('#clinDoctorHint').text(pick.why);
                }
                $('#clinIssueToken').prop('disabled', false);
            }
            $('#clinTokenPanel').show();
        }, true);
    }

    function issueToken() {
        if (!state.patient) return;
        var providerId = $('#clinDoctor').val();
        if (!providerId) { msg(L('chooseDoctor', 'Choose the doctor.'), false); return; }
        var $btn = $('#clinIssueToken').prop('disabled', true);
        var moving = state.relink;
        $.ajax({ type: 'POST', contentType: 'application/json', dataType: 'json',
                 url: moving ? url('clinic/tokens/' + encodeURIComponent(moving.tokenId) + '/relink') : url('clinic/tokens'),
                 data: JSON.stringify(moving ? { providerId: Number(providerId) }
                                             : { patientId: state.patient.id, providerId: Number(providerId) }) })
            .done(function (r) {
                if (r && r.success) {
                    var t = r.data;
                    $('#clinTokenNo').text(t.tokenLabel);
                    $('#clinTokenMeta').text(t.providerName + ' · ' + (t.ahead === 0 ? L('nextInLine', 'next in line') : L('ahead', '{0} ahead', t.ahead)));
                    $('#clinTokenSlip').show();
                    msg(r.message, true);
                    state.doctors = null;   // today's numbers changed
                    if (moving) endRelink();
                } else { msg((r && r.message) || L('issueFailed', 'Could not issue the token.'), false); }
            })
            .fail(function (xhr) { msg(reason(xhr, L('issueFailed', 'Could not issue the token.')), false); })
            .always(function () { $btn.prop('disabled', false); });
    }

    // ── S2: today's queue ───────────────────────────────────────────────────────────────────────────────
    var STATUS_WORDS = { WAITING: L('stWaiting', 'Waiting'), CALLED: L('stCalled', 'Called'), IN_CONSULTATION: L('stWithDoctor', 'With doctor'),
                         PARKED: L('stParked', 'Parked'), COMPLETED: L('stDone', 'Done'), CANCELLED: L('stCancelled', 'Cancelled'),
                         NO_SHOW: L('stNotHere', 'Not here') };

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
                    + (s === 'WAITING' && t.ahead != null ? ' <small class="clin-help">' + escHtml(t.ahead === 0 ? L('next', 'next') : L('ahead', '{0} ahead', t.ahead)) + '</small>' : '')
                    // the park reason only while PARKED: "Done · CBC, LFT pending" read as if the tests were still out
                    + (s === 'PARKED' && t.parkReason ? ' <small class="clin-help">' + escHtml(t.parkReason) + '</small>' : '')
                    // RELINK: why a token left the line ("Moved to Dr B as B-004", "patient asked to cancel")
                    + ((s === 'CANCELLED' || s === 'NO_SHOW') && t.cancelReason ? ' <small class="clin-help clin-cancel-reason">' + escHtml(t.cancelReason) + '</small>' : '') + '</td>'
                    + '<td class="clin-row-actions">' + (live
                        ? '<button type="button" class="btn btn-xs btn-primary clin-relink" data-id="' + escHtml(t.id) + '" data-patient="' + escHtml(t.patientId)
                          + '" data-provider="' + escHtml(t.providerId) + '" data-label="' + escHtml(t.tokenLabel) + '" data-provider-name="' + escHtml(t.providerName || '')
                          + '" title="' + escHtml(L('relinkTip', 'Send this patient to another doctor')) + '">' + escHtml(L('relink', 'Relink')) + '</button> '
                          + '<button type="button" class="btn btn-xs btn-default clin-move" data-action="noShow" data-id="' + escHtml(t.id) + '">' + escHtml(L('stNotHere', 'Not here')) + '</button> '
                          + '<button type="button" class="btn btn-xs btn-danger clin-move" data-action="cancel" data-id="' + escHtml(t.id) + '">' + escHtml(L('cancel', 'Cancel')) + '</button>'
                        // the visit has begun: cancelling is no longer the desk's — shown, not offered
                        : (s === 'IN_CONSULTATION' || s === 'PARKED')
                            ? '<button type="button" class="btn btn-xs btn-danger" disabled title="' + escHtml(L('cantCancelStarted', 'The visit has started — it cannot be cancelled.')) + '">'
                              + escHtml(L('cancel', 'Cancel')) + '</button>'
                            : '') + '</td></tr>');
            });
            $('#clinQueueEmpty').toggle(rows.length === 0);
            $('#clinQueueSummary').html(rows.length
                ? '<span><b>' + waiting + '</b> ' + escHtml(L('sumWaiting', 'waiting')) + '</span><span><b>' + withDoctor + '</b> '
                  + escHtml(L('sumWithDoctor', 'with a doctor')) + '</span><span><b>' + done + '</b> ' + escHtml(L('sumDone', 'done')) + '</span>'
                : '');
            $('#clinQueueUpdated').text(L('updated', 'Updated {0}', new Date().toLocaleTimeString()));
        }).fail(function (xhr) { moduleOff(reason(xhr, '')); });
    }

    function moveToken(id, action) {
        if (action === 'cancel' && typeof uiPromptConfirm === 'function') {
            uiPromptConfirm({ title: L('cancelTokenQ', 'Cancel this token?'),
                              message: L('leavesLine', "The patient leaves today's line. A new token can be issued later."),
                              input: { label: L('cancelReason', 'Reason'), required: true,
                                       requiredMessage: L('cancelReasonNeeded', 'Say why, e.g. the patient asked to cancel.') },
                              confirmText: L('cancelToken', 'Cancel token'), tone: 'warning' })
                .then(function (why) {
                    if (why === null) return;
                    $.ajax({ type: 'POST', url: url('clinic/tokens/' + encodeURIComponent(id) + '/cancel'),
                             contentType: 'application/json', dataType: 'json', data: JSON.stringify({ reason: why }) })
                        .done(function (r) { msg((r && r.message) || L('done', 'Done.'), !!(r && r.success)); loadBoard(); })
                        .fail(function (xhr) { msg(reason(xhr, L('tokenChangeFailed', 'Could not change the token.')), false); loadBoard(); });
                });
            return;
        }
        var title = action === 'cancel' ? L('cancelTokenQ', 'Cancel this token?') : L('notHereQ', 'Mark the patient as not here?');
        var go = function () {
            $.ajax({ type: 'POST', url: url('clinic/tokens/' + encodeURIComponent(id) + '/' + action),
                     contentType: 'application/json', dataType: 'json',
                     data: JSON.stringify({ reason: action === 'cancel' ? 'cancelled at reception' : 'not here when called' }) })
                .done(function (r) { msg((r && r.message) || L('done', 'Done.'), !!(r && r.success)); loadBoard(); })
                .fail(function (xhr) { msg(reason(xhr, L('tokenChangeFailed', 'Could not change the token.')), false); loadBoard(); });
        };
        if (typeof uiConfirm === 'function') {
            uiConfirm({ title: title, message: L('leavesLine', "The patient leaves today's line. A new token can be issued later."),
                        confirmText: action === 'cancel' ? L('cancelToken', 'Cancel token') : L('stNotHere', 'Not here'), tone: 'warning' })
                .then(function (ok) { if (ok) go(); });
        } else { go(); }
    }

    // ── H2: logins of the doctors (owner / admin) ──────────────────────────────────────────────────────
    var team = { loaded: false, byId: {}, list: [] };
    // ── RELINK (2026-10-12): a not-yet-seen patient goes to another doctor — the desk's own search, prefilled ──────
    function startRelink($b) {
        var token = { tokenId: $b.data('id'), label: String($b.data('label')), patientId: Number($b.data('patient')),
                      providerId: Number($b.data('provider')), providerName: String($b.data('provider-name') || '') };
        $.getJSON(url('clinic/patients/' + encodeURIComponent(token.patientId))).done(function (r) {
            if (!r || !r.success || !r.data) { msg((r && r.message) || L('lookupFailed', 'Could not look up the number.'), false); return; }
            state.relink = token;
            show('ReceptionDiv');
            $('#clinPatPhone').val(r.data.phone);
            $('#clinRelinkNote').text(L('relinkNote', 'Moving {0} from {1} — choose the new doctor and press Move.', token.label, token.providerName)).show();
            $('#clinIssueTokenText').text(L('moveHere', 'Move to this doctor'));
            find(true);
        }).fail(function (xhr) { msg(reason(xhr, L('lookupFailed', 'Could not look up the number.')), false); });
    }

    function endRelink() {
        if (!state.relink) return;
        state.relink = null;
        $('#clinRelinkNote').hide().text('');
        $('#clinIssueTokenText').text(L('issueToken', 'Issue token'));
    }

    // ── inline patient edit (2026-10-12): name, CNIC, date of birth, sex — the phone is the patient's identity ──
    function editPatient($b) {
        var id = $b.data('id'), $row = $b.closest('tr');
        if ($row.next().hasClass('clin-pat-edit-row')) { $row.next().remove(); return; }
        $('#clinPatBody .clin-pat-edit-row').remove();
        $.getJSON(url('clinic/patients/' + encodeURIComponent(id))).done(function (r) {
            if (!r || !r.success || !r.data) { msg((r && r.message) || L('patientLoadFailed', 'Could not load the patient.'), false); return; }
            var p = r.data;
            var sexOpt = function (v, label) { return '<option value="' + v + '"' + (p.sex === v ? ' selected' : '') + '>' + escHtml(label) + '</option>'; };
            $row.after('<tr class="clin-pat-edit-row" data-id="' + escHtml(p.id) + '" data-version="' + escHtml(p.version) + '"><td colspan="6">'
                + '<div class="clin-pat-edit">'
                + '<label>' + escHtml(L('name', 'Name')) + ' <input class="form-control input-sm clin-ed-name" value="' + escHtml(p.name || '') + '"></label>'
                + '<label>' + escHtml(L('cnic', 'CNIC')) + ' <input class="form-control input-sm clin-ed-cnic" value="' + escHtml(p.cnic || '') + '"></label>'
                + '<label>' + escHtml(L('dob', 'Date of birth')) + ' <input type="date" class="form-control input-sm clin-ed-dob" value="' + escHtml(p.dateOfBirth || '') + '"></label>'
                + '<label>' + escHtml(L('sex', 'Sex')) + ' <select class="form-control input-sm clin-ed-sex">' + sexOpt('', '—') + sexOpt('M', L('male', 'Male'))
                + sexOpt('F', L('female', 'Female')) + sexOpt('O', L('other', 'Other')) + '</select></label>'
                + '<span class="clin-help">' + escHtml(L('phoneFixed', 'The phone is the patient\'s identity — to change it, register them again.')) + '</span>'
                + '<button type="button" class="btn btn-xs btn-success clin-ed-save">' + escHtml(L('save', 'Save')) + '</button> '
                + '<button type="button" class="btn btn-xs btn-default clin-ed-cancel">' + escHtml(L('close', 'Close')) + '</button>'
                + '</div></td></tr>');
            $row.next().find('.clin-ed-name').trigger('focus');
        }).fail(function (xhr) { msg(reason(xhr, L('patientLoadFailed', 'Could not load the patient.')), false); });
    }

    function savePatient($b) {
        var $r = $b.closest('tr'), id = $r.data('id');
        $b.prop('disabled', true);
        $.ajax({ type: 'PUT', url: url('clinic/patients/' + encodeURIComponent(id)), contentType: 'application/json', dataType: 'json',
                 data: JSON.stringify({ name: $.trim($r.find('.clin-ed-name').val()), cnic: $.trim($r.find('.clin-ed-cnic').val()) || null,
                                        dateOfBirth: $r.find('.clin-ed-dob').val() || null, sex: $r.find('.clin-ed-sex').val() || null,
                                        version: $r.data('version') }) })
            .done(function (r) {
                msg((r && r.message) || L('saved', 'Saved.'), !!(r && r.success));
                if (r && r.success) loadPatients();
            })
            .fail(function (xhr) { msg(reason(xhr, L('patientSaveFailed', 'Could not save the patient.')), false); })
            .always(function () { $b.prop('disabled', false); });
    }

    function isClinicAdmin() { return $('#clinIsAdmin').length > 0; }
    /** RW-2: patients-a-day and "not today" are the front desk's and the administration's (RW-1 D2). */
    function canSetDay() { return $('#clinCanDay').length > 0; }
    /** RW-2: a doctor's day tools (today's limit, not today, usual) — only for canSetDay(). */
    function dayTools(d) {
        return ''
            + '<input type="number" min="0" class="form-control input-sm clin-day-limit" placeholder="' + escHtml(L('limit', 'Limit')) + '" aria-label="' + escHtml(L('todayLimitFor', "Today's limit for {0}", d.name)) + '"/>'
            + '<button type="button" class="btn btn-xs btn-primary clin-day" data-kind="limit">' + escHtml(L('setToday', 'Set today')) + '</button> '
            + (d.closedToday
                ? '<button type="button" class="btn btn-xs btn-default clin-day" data-kind="reset">' + escHtml(L('openAgain', 'Open again')) + '</button>'
                : '<button type="button" class="btn btn-xs btn-default clin-day" data-kind="closed">' + escHtml(L('notToday', 'Not today')) + '</button>'
                  + (d.todayChanged ? ' <button type="button" class="btn btn-xs btn-default clin-day" data-kind="reset">' + escHtml(L('usual', 'Usual')) + '</button>' : ''));
    }
    function withTeam(done) {
        if (!isClinicAdmin()) { done(); return; }
        if (team.loaded) { done(); return; }
        $.getJSON(url('team/users')).done(function (r) {
            team.list = (r && (r.data || r.object)) || [];
            team.byId = {};
            team.list.forEach(function (u) { team.byId[String(u.userId || u.id)] = u; });
            team.loaded = true;
        }).always(done);
    }
    // H4: the pharmacy branches (business stores) — the owner / an admin routes each doctor to one
    var branches = { loaded: false, list: [] };
    function withBranches(done) {
        if (branches.loaded) { done(); return; }
        $.getJSON(url('getStores')).done(function (r) {
            var l = (r && (r.object || r.collection || r.data)) || [];
            branches.list = Array.isArray(l) ? l : [];
            branches.loaded = true;
        }).always(done);
    }
    function branchName(id) {
        var b = branches.list.filter(function (s) { return String(s.id) === String(id); })[0];
        return b ? (b.name || L('branchN', 'Branch {0}', b.id)) : null;
    }
    function pharmacyPicker(d) {
        if (!isClinicAdmin()) {
            return d.pharmacyStoreId ? '<div class="clin-help">' + escHtml(L('pharmacyIs', 'Pharmacy: {0}', branchName(d.pharmacyStoreId) || L('aBranch', 'a branch'))) + '</div>' : '';
        }
        if (branches.list.length < 2 && !d.pharmacyStoreId) return '';   // one branch: nothing to route
        return '<div class="clin-pharmacy"><label class="sr-only" for="clinPh' + escHtml(d.id) + '">' + escHtml(L('pharmacyBranch', 'Pharmacy branch')) + '</label>'
            + '<select id="clinPh' + escHtml(d.id) + '" class="form-control input-sm clin-pharmacy-pick" data-id="' + escHtml(d.id) + '">'
            + '<option value="">' + escHtml(L('pharmacyEvery', 'Pharmacy: every branch')) + '</option>'
            + branches.list.map(function (s) {
                return '<option value="' + escHtml(s.id) + '"' + (String(s.id) === String(d.pharmacyStoreId) ? ' selected' : '') + '>'
                    + escHtml(L('pharmacyIs', 'Pharmacy: {0}', s.name || L('branchN', 'Branch {0}', s.id))) + '</option>';
            }).join('') + '</select></div>';
    }
    function savePharmacy($sel) {
        var v = $sel.val();
        $.ajax({ type: 'POST', url: url('clinic/doctors/' + encodeURIComponent($sel.data('id')) + '/pharmacy'), contentType: 'application/json',
                 dataType: 'json', data: JSON.stringify({ storeId: v ? Number(v) : null }) })
            .done(function (r) { msg((r && r.message) || L('saved', 'Saved.'), !!(r && r.success)); loadDoctorTable(); })
            .fail(function (xhr) { msg(reason(xhr, L('branchFailed', 'Could not save the pharmacy branch.')), false); loadDoctorTable(); });
    }

    function loginCell(d) {
        if (!d.linkedUserId) {
            // .clin-login-link, never .clin-link: that class is the reception's patient → customer "Link now", and both
            // handlers fired on either button (a doctor id was posted as a PATIENT id) — found in the H5b review
            return isClinicAdmin() ? '<button type="button" class="btn btn-xs btn-default clin-login-link" data-id="' + escHtml(d.id) + '">' + escHtml(L('linkLogin', 'Link login')) + '</button>'
                                   : '<span class="clin-help">—</span>';
        }
        var u = team.byId[String(d.linkedUserId)];
        var who = u ? (u.email || ((u.firstName || '') + ' ' + (u.lastName || ''))) : L('linked', 'Linked');
        return '<span class="clin-linked">' + escHtml(who) + '</span>'
            + (isClinicAdmin() ? ' <button type="button" class="btn btn-xs btn-link clin-unlink" data-id="' + escHtml(d.id) + '">' + escHtml(L('unlink', 'Unlink')) + '</button>' : '');
    }
    function startLink($btn) {
        var id = $btn.data('id');
        var taken = {};
        $('#clinDoctorBody tr').each(function () { var l = $(this).attr('data-linked'); if (l) taken[l] = true; });
        // TEAM-1: a REMOVED member (status INACTIVE) is never offered as a doctor's login
        var opts = team.list.filter(function (u) { return !taken[String(u.userId || u.id)] && String(u.status || '').toUpperCase() !== 'INACTIVE'; }).map(function (u) {
            var uid = u.userId || u.id;
            return '<option value="' + escHtml(uid) + '">' + escHtml(u.email || uid) + '</option>';
        }).join('');
        if (!opts) { msg(L('allLinked', 'Every team member is linked already. Register the doctor to make a new login.'), false); return; }
        $btn.closest('td').html('<span class="clin-link-pick"><select class="form-control input-sm clin-link-user" aria-label="' + escHtml(L('loginToLink', 'Login to link')) + '">'
            + '<option value="">' + escHtml(L('chooseLogin', 'Choose a login')) + '</option>' + opts + '</select> '
            + '<button type="button" class="btn btn-xs btn-primary clin-link-save" data-id="' + escHtml(id) + '">' + escHtml(L('link', 'Link')) + '</button></span>');
    }
    function saveLink($btn) {
        var userId = $btn.siblings('.clin-link-user').val();
        if (!userId) { msg(L('chooseLoginToLink', 'Choose the login to link.'), false); return; }
        $.ajax({ type: 'POST', url: url('clinic/doctors/' + encodeURIComponent($btn.data('id')) + '/link'), contentType: 'application/json',
                 dataType: 'json', data: JSON.stringify({ userId: Number(userId) }) })
            .done(function (r) { msg((r && r.message) || L('linkedDot', 'Linked.'), !!(r && r.success)); loadDoctorTable(); })
            .fail(function (xhr) { msg(reason(xhr, L('linkLoginFailed', 'Could not link the login.')), false); loadDoctorTable(); });
    }
    function unlink($btn) {
        var go = function () {
            $.ajax({ type: 'POST', url: url('clinic/doctors/' + encodeURIComponent($btn.data('id')) + '/unlink'), dataType: 'json' })
                .done(function (r) { msg((r && r.message) || L('unlinked', 'Unlinked.'), !!(r && r.success)); loadDoctorTable(); })
                .fail(function (xhr) { msg(reason(xhr, L('unlinkFailed', 'Could not unlink.')), false); });
        };
        if (typeof uiConfirm === 'function') {
            uiConfirm({ title: L('unlinkQ', 'Unlink this login?'), message: L('unlinkMsg', "They can no longer open this doctor's patients unless the owner put them on the Doctor set."),
                        confirmText: L('unlink', 'Unlink'), tone: 'danger' }).then(function (ok) { if (ok) go(); });
        } else { go(); }
    }

    // ── S2: doctors and today's limits ──────────────────────────────────────────────────────────────────
    function loadDoctorTable() {
        withTeam(function () { withBranches(function () { withDoctors(function (doctors) {
            var $b = $('#clinDoctorBody').empty();
            $('#clinDoctorEmpty').toggle(doctors.length === 0);
            doctors.forEach(function (d) {
                var today = d.closedToday ? '<span class="clin-badge warn">' + escHtml(L('notToday', 'Not today')) + '</span>'
                    : (d.todayLimit == null ? escHtml(L('noLimit', 'No limit')) : escHtml(d.todayLimit))
                      + (d.todayChanged ? ' <small class="clin-help">' + escHtml(L('todayOnly', '(today only)')) + '</small>' : '');
                $b.append('<tr data-doctor-id="' + escHtml(d.id) + '"' + (d.linkedUserId ? ' data-linked="' + escHtml(d.linkedUserId) + '"' : '') + '>'
                    + '<td><b>' + escHtml(d.name) + '</b>' + (d.speciality ? '<br><small class="clin-help">' + escHtml(d.speciality) + '</small>' : '') + '</td>'
                    + '<td class="clin-token-cell">' + escHtml(d.tokenPrefix || '—') + '</td>'
                    + '<td>' + (d.usualLimit == null ? escHtml(L('noLimit', 'No limit')) : escHtml(d.usualLimit)) + '</td>'
                    + '<td>' + today + '</td>'
                    + '<td>' + escHtml(d.issuedToday) + '</td><td>' + escHtml(d.waitingNow) + '</td>'
                    + '<td class="clin-login-cell">' + loginCell(d) + pharmacyPicker(d) + '</td>'
                    + '<td class="clin-day-tools">' + (canSetDay() ? dayTools(d) : '')
                    + '</td></tr>');
            });
        }, true); }); });
    }

    function setDay($row, kind) {
        var body = { providerId: Number($row.data('doctor-id')), reset: kind === 'reset', closed: kind === 'closed' };
        if (kind === 'limit') {
            var v = $.trim($row.find('.clin-day-limit').val());
            if (v === '') { msg(L('typeTodayLimit', "Type today's limit (0 = no limit today)."), false); return; }
            body.limit = Number(v);
        }
        $.ajax({ type: 'POST', url: url('clinic/doctors/day'), contentType: 'application/json', dataType: 'json', data: JSON.stringify(body) })
            .done(function (r) { msg((r && r.message) || L('saved', 'Saved.'), !!(r && r.success)); loadDoctorTable(); })
            .fail(function (xhr) { msg(reason(xhr, L('dayFailed', 'Could not save the day.')), false); });
    }

    function addDoctor() {
        var noLimit = $('#clinDocNoLimit').is(':checked');
        var limit = $.trim($('#clinDocLimit').val());
        if (!noLimit && limit === '') { msg(L('typePerDay', 'Type the patients a day, or tick No limit.'), false); return; }
        var body = { name: $.trim($('#clinDocName').val()), speciality: $.trim($('#clinDocSpeciality').val()),
                     fee: $.trim($('#clinDocFee').val()), dailyLimit: noLimit ? null : Number(limit) };
        // H2: with "Create their login" the doctor IS a login (set-password email; My queue opens on their patients)
        var withLogin = $('#clinDocMakeLogin').is(':checked');
        if (withLogin) {
            body.email = $.trim($('#clinDocEmail').val());
            if (!body.email) { msg(L('enterDoctorEmail', "Enter the doctor's email, or untick Create their login."), false); $('#clinDocEmail').focus(); return; }
        }
        var $btn = $('#clinDocSave').prop('disabled', true);
        $.ajax({ type: 'POST', url: url(withLogin ? 'clinic/doctors/register' : 'clinic/doctors'), contentType: 'application/json',
                 dataType: 'json', data: JSON.stringify(body) })
            .done(function (r) {
                if (r && r.success) {
                    msg(r.message, true);
                    $('#clinDoctorForm')[0].reset();
                    $('#clinDocLimit').prop('disabled', false);
                    team.loaded = false;   // a new login exists
                    loadDoctorTable();
                } else { msg((r && r.message) || L('addDoctorFailed', 'Could not add the doctor.'), false); }
            })
            .fail(function (xhr) { msg(reason(xhr, L('addDoctorFailed', 'Could not add the doctor.')), false); })
            .always(function () { $btn.prop('disabled', false); });
    }

    // RW-2: the page opens on the first screen this person may use — the desk on Reception, the doctor on My queue
    var openMyQueueWhenKnown = false;
    // RW-3: a workspace tile asks for its screen (?open=MyQueueDiv); honoured only when this person has that screen
    var OPENABLE = { ReceptionDiv: '#clinNavReception', QueueDiv: '#clinNavQueue', DoctorsDiv: '#clinNavDoctors',
                     ClinicSettingsDiv: '#clinNavSettings', TeamDiv: '#clinNavTeam',
                     PharmacyLinkDiv: '#clinNavPharmacyLink' };
    function openFirst() {
        var wanted = null;
        try { wanted = new URLSearchParams(global.location.search).get('open'); } catch (e) { /* old browser */ }
        if (wanted === 'MyQueueDiv') { openMyQueueWhenKnown = true; return; }   // shown once "which doctor am I" answers
        if (wanted && OPENABLE[wanted] && $(OPENABLE[wanted]).length) { show(wanted); return; }
        if (openOwnScreen()) return;
        openMyQueueWhenKnown = true;                       // loadMe() opens My queue for a doctor
    }
    /** The first screen this person has, in order; false when they have none of them (a doctor). */
    function openOwnScreen() {
        if ($('#clinNavReception').length) { show('ReceptionDiv'); return true; }
        if ($('#clinNavQueue').length) { show('QueueDiv'); return true; }
        if ($('#clinNavDoctors').length) { show('DoctorsDiv'); return true; }
        return false;
    }

    // ── H2: which doctor am I ───────────────────────────────────────────────────────────────────────────
    var me = null;
    function loadMe() {
        $.getJSON(url('clinic/doctors/me')).done(function (r) {
            me = (r && r.success && r.data) || null;
            $('#clinNavMyQueueWrap').toggle(!!(me && me.canConsult));
            if (openMyQueueWhenKnown) {
                openMyQueueWhenKnown = false;
                if (me && me.canConsult) show('MyQueueDiv');
                else openOwnScreen();   // asked for My queue but not a doctor: their own first screen instead
            }
        });
    }

    // ── S3a: the doctor's queue and the consultation ─────────────────────────────────────────────────────
    var DOCTOR_KEY = 'clinic.myDoctor';
    var consultState = { timer: null, encounter: null };

    function rememberedDoctor() { try { return localStorage.getItem(DOCTOR_KEY); } catch (e) { return null; } }
    function rememberDoctor(id) { try { localStorage.setItem(DOCTOR_KEY, String(id)); } catch (e) { /* private window */ } }

    function startMyQueue() {
        withDoctors(function (doctors) {
            var $s = $('#clinMyDoctor').empty();
            doctors.forEach(function (d) { $s.append('<option value="' + escHtml(d.id) + '">' + escHtml(d.name) + '</option>'); });
            var mine = me && me.providerId;
            if (mine) {
                // H2: this login IS a doctor — their own queue, no doctor to choose
                $s.val(String(mine));
                $('#clinMyDoctorWrap').hide();
                $('#clinMeName').text(me.name || '').show();
            } else {
                $('#clinMyDoctorWrap').show();
                $('#clinMeName').hide();
                var keep = rememberedDoctor();
                if (keep && doctors.some(function (d) { return String(d.id) === keep; })) $s.val(keep);
            }
            loadMyQueue();
        }, true);
        stopMyQueue();
        consultState.timer = setInterval(function () { if (!$('#clinConsult').is(':visible')) loadMyQueue(); }, 5000);
    }
    function stopMyQueue() { if (consultState.timer) { clearInterval(consultState.timer); consultState.timer = null; } }

    function myDoctor() { return $('#clinMyDoctor').val(); }

    function loadMyQueue() {
        var id = myDoctor();
        if (!id) return;
        $.getJSON(url('clinic/queue'), { providerId: id }).done(function (r) {
            if (!r || !r.success) { moduleOff(r && r.message); return; }
            var rows = (r.data || []).filter(function (t) { return ['WAITING', 'CALLED', 'IN_CONSULTATION', 'PARKED'].indexOf(t.status) >= 0; });
            var $b = $('#clinQueueBody').empty();
            rows.forEach(function (t) {
                var act = t.status === 'WAITING' ? '<button type="button" class="btn btn-xs btn-default clin-q-call" data-id="' + escHtml(t.id) + '">' + escHtml(L('call', 'Call')) + '</button>'
                    : t.status === 'CALLED' ? '<button type="button" class="btn btn-xs btn-success clin-q-start" data-id="' + escHtml(t.id) + '">' + escHtml(L('start', 'Start')) + '</button>'
                    : '<button type="button" class="btn btn-xs btn-primary clin-q-start" data-id="' + escHtml(t.id) + '">' + escHtml(L('open', 'Open')) + '</button>';
                $b.append('<tr data-token-id="' + escHtml(t.id) + '"><td class="clin-token-cell">' + escHtml(t.tokenLabel) + '</td>'
                    + '<td>' + escHtml(t.patientName || '') + (t.status === 'PARKED' && t.parkReason ? ' <span class="clin-help">· ' + escHtml(t.parkReason) + '</span>' : '')
                    + '</td><td class="clin-mrn-cell">' + escHtml(t.mrn || '') + '</td>'
                    + '<td><span class="clin-status s-' + escHtml(String(t.status).toLowerCase()) + '">' + escHtml(STATUS_WORDS[t.status] || t.status) + '</span></td>'
                    + '<td class="clin-row-actions">' + act + '</td></tr>');
            });
            $('#clinMyQueueEmpty').toggle(rows.length === 0);
        }).fail(function (xhr) { msg(reason(xhr, L('queueFailed', 'Could not load the queue.')), false); });
    }

    function callNext() {
        var id = myDoctor();
        if (!id) { msg(L('whichDoctor', 'Choose which doctor you are.'), false); return; }
        $.ajax({ type: 'POST', url: url('clinic/consult/next') + '?providerId=' + encodeURIComponent(id), dataType: 'json' })
            .done(function (r) { msg((r && r.message) || L('done', 'Done.'), !!(r && r.success)); loadMyQueue(); })
            .fail(function (xhr) { msg(reason(xhr, L('callNextFailed', 'Could not call the next patient.')), false); });
    }

    function callToken(tokenId) {
        $.ajax({ type: 'POST', url: url('clinic/tokens/' + encodeURIComponent(tokenId) + '/call'), contentType: 'application/json', dataType: 'json', data: '{}' })
            .done(function (r) { msg((r && r.message) || L('called', 'Called.'), !!(r && r.success)); loadMyQueue(); })
            .fail(function (xhr) { msg(reason(xhr, L('callFailed', 'Could not call the patient.')), false); loadMyQueue(); });
    }

    /** Start (CALLED) or reopen (with the doctor / parked) — the server makes or returns the one visit of the token. */
    function startToken(tokenId) {
        $.ajax({ type: 'POST', url: url('clinic/consult/tokens/' + encodeURIComponent(tokenId) + '/start'), dataType: 'json' })
            .done(function (r) { if (r && r.success) showEncounter(r.data); else msg((r && r.message) || L('startFailed', 'Could not start.'), false); })
            .fail(function (xhr) { msg(reason(xhr, L('startConsultFailed', 'Could not start the consultation.')), false); });
    }

    function vitalsLine(e) {
        var parts = [];
        if (e.bloodPressure) parts.push('BP ' + e.bloodPressure);
        if (e.pulse != null) parts.push(L('pulseN', 'Pulse {0}', e.pulse));
        if (e.temperatureF != null) parts.push(e.temperatureF + ' °F');
        if (e.spo2 != null) parts.push('SpO2 ' + e.spo2 + '%');
        if (e.weightKg != null) parts.push(e.weightKg + ' kg');
        return parts.join(' · ');
    }

    function noteItems(notes) {
        return (notes || []).map(function (n) {
            var at = fmtDateTime(n.createdAt);
            return '<li><span class="clin-note-at">' + escHtml(at) + (n.amendsNoteId ? ' · ' + escHtml(L('correction', 'correction')) : '') + '</span>'
                + '<span class="clin-note-body">' + escHtml(n.body) + '</span></li>';
        }).join('');
    }

    function showEncounter(e) {
        consultState.encounter = e;
        var p = e.patient || {};
        // Two identifiers before anything clinical: the name with the MRN, and the date of birth.
        $('#clinIdBanner').html('<div class="clin-id-name">' + escHtml(p.name || '') + '</div>'
            + '<div class="clin-id-facts"><span class="clin-mrn">' + escHtml(p.mrn || '') + '</span>'
            + (p.dateOfBirth ? '<span>' + escHtml(p.dateOfBirth) + (p.ageYears != null ? ' · ' + escHtml(L('ageYears', '{0} y', p.ageYears)) : '') + '</span>' : '<span class="clin-help">' + escHtml(L('noDob', 'no date of birth recorded')) + '</span>')
            + (p.sex ? '<span>' + escHtml(sexLabel(p.sex)) + '</span>' : '')
            + '<span class="clin-token-cell">' + escHtml(e.tokenLabel || '') + '</span>'
            + '<span class="clin-status s-' + escHtml(String(e.tokenStatus || '').toLowerCase()) + '" id="clinStatus" data-status="' + escHtml(String(e.tokenStatus || '')) + '">' + escHtml(STATUS_WORDS[e.tokenStatus] || String(e.tokenStatus || '')) + '</span></div>');
        $('#clinComplaint').val(e.chiefComplaint || '');
        $('#clinVitalBp').val(e.bloodPressure || '');
        $('#clinVitalPulse').val(e.pulse != null ? e.pulse : '');
        $('#clinVitalTemp').val(e.temperatureF != null ? e.temperatureF : '');
        $('#clinVitalSpo2').val(e.spo2 != null ? e.spo2 : '');
        $('#clinVitalWeight').val(e.weightKg != null ? e.weightKg : '');
        $('#clinVitalHeight').val(e.heightCm != null ? e.heightCm : '');
        var done = e.status === 'COMPLETED';
        $('#clinVitalsForm :input').prop('disabled', done);
        $('#clinComplete').prop('disabled', done);
        $('#clinNotes').html(noteItems(e.notes));
        var hist = (e.history || []).map(function (h) {
            var at = fmtDate(h.startedAt);   // the viewer's DAY of the visit
            return '<div class="clin-visit"><div class="clin-visit-head"><b>' + escHtml(at) + '</b> · ' + escHtml(h.providerName || '')
                + (h.chiefComplaint ? ' · ' + escHtml(h.chiefComplaint) : '') + '</div>'
                + (vitalsLine(h) ? '<div class="clin-help">' + escHtml(vitalsLine(h)) + '</div>' : '')
                + ((h.notes || []).length ? '<ol class="clin-note-list">' + noteItems(h.notes) + '</ol>' : '') + '</div>';
        }).join('');
        $('#clinHistory').html(hist || '<p class="clin-empty">' + escHtml(L('firstVisit', 'First visit.')) + '</p>');
        rx.lines = (e.rxLines || []).map(function (l) { return $.extend({}, l); });
        renderRx();
        loadMedicines();
        if (!e.rxId) loadTemplates();
        $('#clinParkWrap').toggle(!done && e.tokenStatus === 'IN_CONSULTATION');
        $('#clinMyQueueCard').hide();
        $('#clinConsult').show();
    }

    function saveVitals() {
        var e = consultState.encounter; if (!e) return;
        var body = { chiefComplaint: $('#clinComplaint').val(), bloodPressure: $('#clinVitalBp').val(), pulse: $('#clinVitalPulse').val(),
                     temperatureF: $('#clinVitalTemp').val(), spo2: $('#clinVitalSpo2').val(), weightKg: $('#clinVitalWeight').val(),
                     heightCm: $('#clinVitalHeight').val(), version: e.version };
        $.ajax({ type: 'PUT', url: url('clinic/consult/encounters/' + e.id), contentType: 'application/json', dataType: 'json', data: JSON.stringify(body) })
            .done(function (r) { if (r && r.success) { msg(r.message, true); showEncounter(r.data); } else msg((r && r.message) || L('notSaved', 'Not saved.'), false); })
            .fail(function (xhr) { msg(reason(xhr, L('notSaved', 'Not saved.')), false); });
    }

    function addNote() {
        var e = consultState.encounter; if (!e) return;
        $.ajax({ type: 'POST', url: url('clinic/consult/encounters/' + e.id + '/notes'), contentType: 'application/json', dataType: 'json',
                 data: JSON.stringify({ body: $('#clinNote').val() }) })
            .done(function (r) { if (r && r.success) { $('#clinNote').val(''); msg(r.message, true); showEncounter(r.data); } else msg((r && r.message) || L('notAdded', 'Not added.'), false); })
            .fail(function (xhr) { msg(reason(xhr, L('noteFailed', 'The note was not added.')), false); });
    }

    function completeVisit() {
        var e = consultState.encounter; if (!e) return;
        var go = function () {
            $.ajax({ type: 'POST', url: url('clinic/consult/encounters/' + e.id + '/complete'), dataType: 'json' })
                .done(function (r) { if (r && r.success) { msg(r.message, true); showEncounter(r.data); } else msg((r && r.message) || L('notCompleted', 'Not completed.'), false); })
                .fail(function (xhr) { msg(reason(xhr, L('completeFailed', 'The visit was not completed.')), false); });
        };
        if (typeof uiConfirm === 'function') {
            uiConfirm({ title: L('completeQ', 'Complete this visit?'), message: L('completeMsg', 'The patient leaves your queue. Notes can still be added to correct the record.'),
                        confirmText: L('completeVisit', 'Complete visit'), tone: 'primary' }).then(function (ok) { if (ok) go(); });
        } else { go(); }
    }

    function backToQueue() { consultState.encounter = null; $('#clinConsult').hide(); $('#clinMyQueueCard').show(); loadMyQueue(); }

    // ── S3b-1: the prescription (the doctor's working list; nothing reaches the pharmacy until Submit) ─────────
    var rx = { lines: [], medicines: null, byName: {} };

    function loadMedicines() {
        if (rx.medicines || typeof ProductPicker === 'undefined') return;
        ProductPicker.load(function (list) {
            rx.medicines = list || [];
            rx.byName = {};
            var html = rx.medicines.map(function (p) {
                var name = String(p.name || L('productN', 'Product #{0}', p.id));
                if (!rx.byName[name.toLowerCase()]) rx.byName[name.toLowerCase()] = p;
                return '<option value="' + escHtml(name) + '">' + (p.formula ? escHtml(p.formula) : '') + '</option>';
            }).join('');
            $('#clinRxMedicines').html(html);
        });
    }

    function rxLocked() { var e = consultState.encounter; return !!(e && e.rxId); }

    function renderRx() {
        var locked = rxLocked();
        var $b = $('#clinRxBody').empty();
        var cell = function (l, i, f, cls, mode) {
            if (locked) return '<td' + (cls ? ' class="' + cls + '"' : '') + '>' + escHtml(l[f] == null ? '' : l[f]) + '</td>';
            return '<td><input type="text" class="form-control input-sm clin-rx-edit' + (cls ? ' ' + cls : '') + '" data-i="' + i + '" data-f="' + f + '"'
                + (mode ? ' inputmode="' + mode + '"' : '') + ' maxlength="100" value="' + escHtml(l[f] == null ? '' : l[f])
                + '" aria-label="' + escHtml(f + ' of ' + l.medicineName) + '"/></td>';
        };
        rx.lines.forEach(function (l, i) {
            $b.append('<tr><td>' + escHtml(l.medicineName) + '</td>' + cell(l, i, 'quantity', 'clin-num', 'numeric')
                + cell(l, i, 'dosage') + cell(l, i, 'frequency') + cell(l, i, 'duration')
                + '<td class="clin-row-actions">' + (locked ? '' : '<button type="button" class="btn btn-xs btn-default clin-rx-remove" data-i="' + i
                    + '" aria-label="' + escHtml(L('removeX', 'Remove {0}', l.medicineName)) + '">' + escHtml(L('remove', 'Remove')) + '</button>') + '</td></tr>');
        });
        $('#clinRxEmpty').toggle(rx.lines.length === 0);
        // whole blocks are hidden, never .btn (theme.css forces .btn to display:inline-flex !important)
        $('#clinRxAdd').toggle(!locked);
        $('#clinRxTemplates').toggle(!locked);
        $('#clinRxActions').toggle(!locked);
        var e = consultState.encounter;
        if (locked) {
            var at = fmtDateTime(e.rxSubmittedAt);
            $('#clinRxSent').text(L('sentToPharmacy', 'Sent to the pharmacy · {0} · {1}', e.tokenLabel || '', at)).show();
            loadPharmacyCopy(e.id);
        } else {
            $('#clinRxSent').hide();
            $('#clinRxPharmacy').hide().empty();
        }
    }

    // ── H3c: what the pharmacy did with the prescription (read when a sent prescription is shown) ──────────
    var RX_WORDS = { PENDING: L('rxPending', 'Waiting at the pharmacy'), PARTIALLY_DISPENSED: L('rxPartly', 'Partly dispensed'),
                     FULLY_DISPENSED: L('rxDispensed', 'Dispensed'), CANCELLED: L('rxCancelled', 'Cancelled by the pharmacy'),
                     EXPIRED: L('rxExpired', 'Expired') };
    function loadPharmacyCopy(encounterId) {
        $.getJSON(url('clinic/consult/encounters/' + encodeURIComponent(encounterId) + '/rx/pharmacy')).done(function (r) {
            var d = (r && r.success && r.data) || {};
            var $p = $('#clinRxPharmacy').empty();
            if (d.unavailable) { $p.html('<p class="clin-help">' + escHtml(d.unavailable) + '</p>').show(); return; }
            if (!d.status) { $p.hide(); return; }
            var fmt = function (s) {
                if (!s) return '—';
                return [s.qty, s.dose, s.freq, s.days].filter(function (x) { return x !== null && x !== undefined && x !== ''; }).join(' · ');
            };
            var html = '<div class="clin-rx-status s-' + escHtml(String(d.status).toLowerCase()) + '"><b>' + escHtml(L('atPharmacy', 'At the pharmacy:')) + '</b> '
                + escHtml(RX_WORDS[d.status] || d.status) + '</div>';
            var changes = d.amendments || [];
            if (changes.length) {
                html += '<div class="clin-rx-changed"><b>' + escHtml(L('changedByPharmacy', 'Changed by the pharmacy')) + '</b><ol>' + changes.map(function (a) {
                    var when = fmtDateTime(a.amendedAt);
                    var lines = (a.changes || []).map(function (c) {
                        var what = !c.before ? L('added', 'added') : !c.after ? L('removed', 'removed') : L('changed', 'changed');
                        return '<li>' + escHtml(c.medicine || '') + ' ' + what + ': ' + escHtml(fmt(c.before)) + ' → ' + escHtml(fmt(c.after)) + '</li>';
                    }).join('');
                    return '<li><span class="clin-note-at">' + escHtml(when) + ' · ' + escHtml(a.kind === 'CANCEL' ? L('cancelledLower', 'cancelled') : L('changed', 'changed')) + '</span>'
                        + '<div class="clin-rx-reason">' + escHtml(a.reason || '') + '</div>' + (lines ? '<ul>' + lines + '</ul>' : '') + '</li>';
                }).join('') + '</ol></div>';
            }
            var items = d.items || [];
            if (items.length && (changes.length || d.status !== 'PENDING')) {
                html += '<table class="table clin-table clin-rx-now"><thead><tr><th>' + escHtml(L('now', 'Now')) + '</th><th>' + escHtml(L('qty', 'Qty'))
                    + '</th><th>' + escHtml(L('dose', 'Dose')) + '</th><th>' + escHtml(L('given', 'Given')) + '</th></tr></thead><tbody>'
                    + items.map(function (i) {
                        return '<tr><td>' + escHtml(i.medicineName || '') + '</td><td class="clin-num">' + escHtml(i.quantity) + '</td><td>'
                            + escHtml(i.dosage || '') + '</td><td class="clin-num">' + escHtml(i.dispensedQuantity == null ? 0 : i.dispensedQuantity) + '</td></tr>';
                    }).join('') + '</tbody></table>';
            }
            $p.html(html).show();
        });
    }

    function addRxLine() {
        var typed = $.trim($('#clinRxMedicine').val());
        var p = rx.byName[typed.toLowerCase()];
        if (!p) { msg(typed ? L('notInList', '"{0}" is not in the pharmacy\'s list. Choose it from the suggestions.', typed) : L('chooseMedicine', 'Choose a medicine.'), false); $('#clinRxMedicine').focus(); return; }
        if (rx.lines.some(function (l) { return String(l.productId) === String(p.id); })) {
            msg(L('alreadyOnRx', '{0} is on the prescription already. Remove it to change it.', p.name), false); return;
        }
        var q = $.trim($('#clinRxQty').val());
        if (!/^\d+$/.test(q) || +q < 1 || +q > 10000) { msg(L('qtyRange', 'Quantity: a whole number from 1 to 10000.'), false); $('#clinRxQty').focus(); return; }
        rx.lines.push({ productId: p.id, medicineName: p.name, quantity: q, dosage: $.trim($('#clinRxDose').val()),
                        frequency: $.trim($('#clinRxFreq').val()), duration: $.trim($('#clinRxDays').val()) });
        $('#clinRxMedicine, #clinRxQty, #clinRxDose, #clinRxFreq, #clinRxDays').val('');
        renderRx();
        $('#clinRxMedicine').focus();
    }

    /** Saves the list as a whole; resolves with the fresh visit (or rejects after saying why). */
    function saveRx(quiet) {
        var e = consultState.encounter; var d = $.Deferred();
        if (!e) return d.reject().promise();
        $.ajax({ type: 'PUT', url: url('clinic/consult/encounters/' + e.id + '/rx'), contentType: 'application/json', dataType: 'json',
                 data: JSON.stringify({ lines: rx.lines }) })
            .done(function (r) {
                if (r && r.success) { if (!quiet) msg(r.message, true); showEncounter(r.data); d.resolve(r.data); }
                else { msg((r && r.message) || L('notSaved', 'Not saved.'), false); d.reject(); }
            })
            .fail(function (xhr) { msg(reason(xhr, L('rxNotSaved', 'The prescription was not saved.')), false); d.reject(); });
        return d.promise();
    }

    function submitRx() {
        var e = consultState.encounter; if (!e) return;
        if (!rx.lines.length) { msg(L('addOneMedicine', 'Add at least one medicine, then Submit.'), false); return; }
        var go = function () {
            $('#clinRxSubmit').prop('disabled', true);
            saveRx(true).then(function (fresh) {
                return $.ajax({ type: 'POST', url: url('clinic/consult/encounters/' + fresh.id + '/rx/submit'), dataType: 'json' });
            }).done(function (r) {
                if (r && r.success) { msg(r.message, true); showEncounter(r.data); }
                else if (r) msg(r.message || L('notSent', 'Not sent.'), false);
            }).fail(function (xhr) { if (xhr && xhr.status !== undefined) msg(reason(xhr, L('notSentRetry', 'Not sent. Press Submit again — it will not be sent twice.')), false); })
              .always(function () { $('#clinRxSubmit').prop('disabled', false); });
        };
        if (typeof uiConfirm === 'function') {
            uiConfirm({ title: L('sendQ', 'Send to the pharmacy?'),
                        message: L('sendMsg', '{0} medicine(s) for {1}. After this the prescription cannot be changed here.', rx.lines.length, (e.patient || {}).name || L('thePatient', 'the patient')),
                        confirmText: L('submit', 'Submit'), tone: 'primary' })
                .then(function (ok) { if (ok) go(); });
        } else { go(); }
    }

    function parkVisit() {
        var e = consultState.encounter; if (!e) return;
        var why = $.trim($('#clinParkReason').val());
        if (!why) { msg(L('parkWhy', 'Say why the patient is parked, e.g. CBC pending.'), false); $('#clinParkReason').focus(); return; }
        var go = function () {
            $.ajax({ type: 'POST', url: url('clinic/tokens/' + encodeURIComponent(e.tokenId) + '/park'), contentType: 'application/json',
                     dataType: 'json', data: JSON.stringify({ reason: why }) })
                .done(function (r) { msg((r && r.message) || L('parked', 'Parked.'), !!(r && r.success)); if (r && r.success) { $('#clinParkReason').val(''); backToQueue(); } })
                .fail(function (xhr) { msg(reason(xhr, L('notParked', 'Not parked.')), false); });
        };
        // unsaved medicines would be lost on the way out: save them first (the pharmacy sees nothing until Submit)
        if (rx.lines.length && !rxLocked()) saveRx(true).then(go); else go();
    }

    // ── S3b-2: templates (a starting point; nothing is saved to the visit until Save / Submit) ────────────────
    var tpl = { list: [] };

    function loadTemplates(selectId) {
        $.getJSON(url('clinic/consult/templates')).done(function (r) {
            tpl.list = (r && r.success && r.data) || [];
            var $s = $('#clinRxTemplate').empty().append('<option value="">' + escHtml(tpl.list.length ? L('chooseTemplate', 'Choose a template') : L('noTemplates', 'No templates yet')) + '</option>');
            tpl.list.forEach(function (t) {
                $s.append('<option value="' + escHtml(t.id) + '">' + escHtml(t.name) + ' (' + (t.lines || []).length + ')</option>');
            });
            if (selectId) $s.val(String(selectId));
        });
    }

    function chosenTemplate() {
        var id = $('#clinRxTemplate').val();
        return tpl.list.filter(function (t) { return String(t.id) === String(id); })[0];
    }

    function useTemplate() {
        var t = chosenTemplate();
        if (!t) { msg(L('chooseTemplateDot', 'Choose a template.'), false); return; }
        if (!rx.medicines) { msg(L('listLoading', "The pharmacy's list is still loading. Try again in a moment."), false); return; }
        var inList = {};
        rx.medicines.forEach(function (p) { inList[String(p.id)] = true; });
        var added = 0, already = [], gone = [];
        (t.lines || []).forEach(function (l) {
            if (!inList[String(l.productId)]) { gone.push(l.medicineName); return; }
            if (rx.lines.some(function (x) { return String(x.productId) === String(l.productId); })) { already.push(l.medicineName); return; }
            rx.lines.push($.extend({}, l));
            added++;
        });
        renderRx();
        var words = L('usedTemplate', '{0} medicine(s) from "{1}". Check them, then Save or Submit.', added, t.name);
        if (already.length) words += ' ' + L('alreadyListed', 'Already on the list: {0}.', already.join(', '));
        // a medicine the pharmacy no longer has is NAMED, never silently dropped
        if (gone.length) words += ' ' + L('noLongerInList', "Not in the pharmacy's list any more: {0}.", gone.join(', '));
        msg(words, gone.length === 0);
    }

    function saveTemplate() {
        var name = $.trim($('#clinRxTemplateName').val());
        if (!name) { msg(L('templateName', 'Give the template a name, e.g. Fever + Flu.'), false); $('#clinRxTemplateName').focus(); return; }
        if (!rx.lines.length) { msg(L('medicinesFirst', 'Add the medicines first, then save them as a template.'), false); return; }
        $.ajax({ type: 'POST', url: url('clinic/consult/templates'), contentType: 'application/json', dataType: 'json',
                 data: JSON.stringify({ name: name, lines: rx.lines }) })
            .done(function (r) {
                if (r && r.success) { msg(r.message, true); $('#clinRxTemplateName').val(''); loadTemplates(r.data.id); }
                else msg((r && r.message) || L('notSaved', 'Not saved.'), false);
            })
            .fail(function (xhr) { msg(reason(xhr, L('templateNotSaved', 'The template was not saved.')), false); });
    }

    function retireTemplate() {
        var t = chosenTemplate();
        if (!t) { msg(L('chooseTemplateToRemove', 'Choose the template to remove.'), false); return; }
        var go = function () {
            $.ajax({ type: 'POST', url: url('clinic/consult/templates/' + encodeURIComponent(t.id) + '/retire'), dataType: 'json' })
                .done(function (r) { msg((r && r.message) || L('removedDot', 'Removed.'), !!(r && r.success)); loadTemplates(); })
                .fail(function (xhr) { msg(reason(xhr, L('notRemoved', 'Not removed.')), false); });
        };
        if (typeof uiConfirm === 'function') {
            uiConfirm({ title: L('removeTemplateQ', 'Remove "{0}"?', t.name), message: L('removeTemplateMsg', 'Prescriptions already written from it are not changed.'),
                        confirmText: L('remove', 'Remove'), tone: 'danger' }).then(function (ok) { if (ok) go(); });
        } else { go(); }
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
        $(document).on('click', '.clin-relink', function () { startRelink($(this)); });
        $(document).on('click', '.clin-pat-edit-btn', function () { editPatient($(this)); });
        $(document).on('click', '.clin-ed-save', function () { savePatient($(this)); });
        $(document).on('click', '.clin-ed-cancel', function () { $(this).closest('tr').remove(); });
        $(document).on('click', '.clin-q-call', function () { callToken($(this).data('id')); });
        $(document).on('click', '.clin-login-link', function () { startLink($(this)); });   // H5b: its own class (see loginCell)
        $(document).on('click', '.clin-link-save', function () { saveLink($(this)); });
        $(document).on('click', '.clin-unlink', function () { unlink($(this)); });
        $(document).on('change', '.clin-pharmacy-pick', function () { savePharmacy($(this)); });
        loadMe();
        $(document).on('click', '.clin-q-start', function () { startToken($(this).data('id')); });
        $('#clinCallNext').on('click', callNext);
        $('#clinMyDoctor').on('change', function () { rememberDoctor(this.value); loadMyQueue(); });
        // the message is a fixed notice now: a click dismisses it (an error stays until read)
        $('#clinMsg').on('click', function () { $(this).stop(true, true).hide(); });
        $(document).on('click', '.clin-rx-remove', function () { rx.lines.splice(+$(this).data('i'), 1); renderRx(); });
        $(document).on('input', '.clin-rx-edit', function () { var l = rx.lines[+$(this).data('i')]; if (l) l[$(this).data('f')] = this.value; });
        $('#clinRxDays').on('keydown', function (ev) { if (ev.key === 'Enter') { ev.preventDefault(); addRxLine(); } });
        $(document).on('click', '.clin-day', function () { setDay($(this).closest('tr'), $(this).data('kind')); });
        $('#clinDocNoLimit').on('change', function () { $('#clinDocLimit').prop('disabled', this.checked).val(''); });
        $(document).on('visibilitychange', function () {
            if (document.hidden) stopBoard();
            else if ($('#QueueDiv').is(':visible') && !state.boardTimer) startBoard();
        });
        openFirst();
    });

    global.Clinic = { show: show, find: find, register: register, reset: reset, addFamily: addFamily,
                      issueToken: issueToken, addDoctor: addDoctor,
                      saveVitals: saveVitals, addNote: addNote, completeVisit: completeVisit, backToQueue: backToQueue,
                      addRxLine: addRxLine, saveRx: function () { saveRx(false); }, submitRx: submitRx, parkVisit: parkVisit,
                      useTemplate: useTemplate, saveTemplate: saveTemplate, retireTemplate: retireTemplate };
})(window);
