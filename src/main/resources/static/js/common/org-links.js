/*
 * HC-1c (2026-10-15) — the clinic ↔ pharmacy link, both sides (fragments/org-links.html).
 *   Pharmacy: #LinkedClinicsDiv — the "Clinics may send us prescriptions" switch, and the clinics (Accept / End link).
 *   Clinic:   #PharmacyLinkDiv  — find a pharmacy, send a link request, see its status, End link.
 * Every rule is auth-service's (/api/auth/links via the monolith's /links); a refusal carries a stable `reason` code,
 * shown here in the person's language (ui.js.link.<CODE>) with the server's sentence as the fallback.
 */
(function (global, $) {
	'use strict';
	if (!$) { return; }

	function base() { return global.serverContext || '/'; }
	function t(key, fallback) {
		var v = typeof global.t === 'function' ? global.t(key) : key;
		return (!v || v === key) ? fallback : v;
	}
	function esc(s) { return global.escHtml ? global.escHtml(String(s == null ? '' : s)) : String(s == null ? '' : s); }
	function ok(r) { return r && (r.success === true || r.status === 'SUCCESS'); }
	function data(r) { return r ? (r.data !== undefined ? r.data : r.object) : null; }
	function refusal(r, fallback) {
		var code = r && r.reason;
		var said = (r && r.message) || fallback;
		return code ? t('ui.js.link.' + code, said) : said;
	}
	function note($el, text, bad) {
		$el.removeClass('alert-success alert-danger').addClass(bad ? 'alert-danger' : 'alert-success').text(text).show();
	}
	function day(iso) { return iso ? String(iso).substring(0, 10) : ''; }
	function statusWord(s) {
		return { REQUESTED: t('ui.js.link.waiting', 'Waiting'), ACTIVE: t('ui.js.link.linked', 'Linked'),
		         ENDED: t('ui.js.link.ended', 'Ended') }[s] || s;
	}
	/** The end-of-link dialog: a reason is required (the server refuses an empty one too). */
	function askReason(title, done) {
		global.uiPromptConfirm({
			title: title,
			message: t('ui.js.link.endMsg', 'New prescriptions stop; nothing already sent is deleted. Both businesses keep a record of why.'),
			input: { label: t('ui.js.link.reason', 'Reason'), required: true,
			         requiredMessage: t('ui.js.link.reasonNeeded', 'Say why the link is ending.') },
			confirmText: t('ui.js.link.end', 'End link'), tone: 'danger'
		}).then(function (why) { if (why !== null && String(why).trim()) done(String(why).trim()); });
	}
	function confirmThen(title, message, okText, done) {
		global.uiConfirm({ title: title, message: message, confirmText: okText })
			.then(function (yes) { if (yes) done(); });
	}

	// ── the pharmacy's side ─────────────────────────────────────────────────────────────────────────────────────
	function showLinkedClinics() {
		$('.formDiv').hide();
		$('#LinkedClinicsDiv').show();
		$('#lcMsg').hide();
		$.getJSON(base() + 'links/settings').done(function (r) {
			if (ok(r)) $('#lcAccept').prop('checked', !!(data(r) || {}).acceptClinics);
		});
		loadPharmacyLinks();
	}
	function loadPharmacyLinks() {
		var $tb = $('#lcLinks tbody').empty();
		$.getJSON(base() + 'links').done(function (r) {
			var rows = ok(r) ? (data(r) || []) : [];
			if (!rows.length) {
				$tb.append($('<tr>').append($('<td colspan="4" class="text-muted">').text(t('ui.js.link.noClinics', 'No clinics have asked to link yet.'))));
				return;
			}
			rows.forEach(function (l) {
				var $tr = $('<tr>').attr('data-link-id', l.id);
				$tr.append($('<td>').text(l.otherName || ''));
				$tr.append($('<td>').text(statusWord(l.status) + (l.status === 'ENDED' && l.endReason ? ' — ' + l.endReason : '')));
				$tr.append($('<td>').text(day(l.acceptedAt || l.requestedAt)));
				var $act = $('<td>');
				if (l.status === 'REQUESTED') {
					$('<button type="button" class="btn btn-xs btn-success lc-accept">').text(t('ui.js.link.accept', 'Accept'))
						.on('click', function () { acceptLink(l); }).appendTo($act);
				}
				if (l.status !== 'ENDED') {
					$('<button type="button" class="btn btn-xs btn-default lc-end" style="margin-left:6px">').text(t('ui.js.link.end', 'End link'))
						.on('click', function () { endLink(l, $('#lcMsg'), loadPharmacyLinks); }).appendTo($act);
				}
				$tr.append($act);
				$tb.append($tr);
			});
		});
	}
	function acceptLink(l) {
		confirmThen(t('ui.js.link.acceptQ', 'Accept this clinic?'),
			t('ui.js.link.acceptMsg', '{0} can then send prescriptions to this pharmacy. You can end the link at any time.').replace('{0}', l.otherName || ''),
			t('ui.js.link.accept', 'Accept'), function () {
				$.post(base() + 'links/' + encodeURIComponent(l.id) + '/accept').done(function (r) {
					if (ok(r)) note($('#lcMsg'), t('ui.js.link.accepted', 'Linked.'), false);
					else note($('#lcMsg'), refusal(r, t('ui.js.link.failed', 'Could not save. Please try again.')), true);
					loadPharmacyLinks();
				}).fail(function () { note($('#lcMsg'), t('ui.js.link.failed', 'Could not save. Please try again.'), true); });
			});
	}
	function saveAccept() {
		var on = $('#lcAccept').is(':checked');
		$.post(base() + 'links/settings', { acceptClinics: on }).done(function (r) {
			if (ok(r)) note($('#lcMsg'), on ? t('ui.js.link.acceptOn', 'Saved. Clinics can now find this pharmacy by its name.')
				: t('ui.js.link.acceptOff', 'Saved. Clinics can no longer find this pharmacy; existing links stay until ended.'), false);
			else { note($('#lcMsg'), refusal(r, t('ui.js.link.failed', 'Could not save. Please try again.')), true); $('#lcAccept').prop('checked', !on); }
		}).fail(function () { note($('#lcMsg'), t('ui.js.link.failed', 'Could not save. Please try again.'), true); $('#lcAccept').prop('checked', !on); });
	}

	// ── both sides ──────────────────────────────────────────────────────────────────────────────────────────────
	function endLink(l, $msg, reload) {
		askReason(t('ui.js.link.endQ', 'End the link with {0}?').replace('{0}', l.otherName || ''), function (why) {
			$.post(base() + 'links/' + encodeURIComponent(l.id) + '/end', { reason: why }).done(function (r) {
				if (ok(r)) note($msg, t('ui.js.link.endedMsg', 'The link has ended.'), false);
				else note($msg, refusal(r, t('ui.js.link.failed', 'Could not save. Please try again.')), true);
				reload();
			}).fail(function () { note($msg, t('ui.js.link.failed', 'Could not save. Please try again.'), true); });
		});
	}

	// ── the clinic's side ───────────────────────────────────────────────────────────────────────────────────────
	var current = null;
	function showPharmacyLink() {
		$('#plMsg').hide();
		$('#plResults tbody').empty();
		loadClinicLink();
	}
	function loadClinicLink() {
		$.getJSON(base() + 'links').done(function (r) {
			var rows = ok(r) ? (data(r) || []) : [];
			current = rows.filter(function (l) { return l.status === 'REQUESTED' || l.status === 'ACTIVE'; })[0] || null;
			var $s = $('#plStatus').empty();
			if (!current) {
				$s.text(t('ui.js.link.none', 'No pharmacy linked.'));
				$('#plFindWrap').show();
				$('#plEnd').hide();
			} else if (current.status === 'REQUESTED') {
				$s.text(t('ui.js.link.waitingFor', 'Waiting for {0} to accept.').replace('{0}', current.otherName || ''));
				$('#plFindWrap').hide();
				$('#plEnd').show().text(t('ui.js.link.cancelRequest', 'Cancel request'));
			} else {
				$s.text(t('ui.js.link.linkedTo', 'Linked to {0}.').replace('{0}', current.otherName || ''));
				$('#plFindWrap').hide();
				$('#plEnd').show().text(t('ui.js.link.end', 'End link'));
			}
		});
	}
	function findPharmacies() {
		var q = $.trim($('#plSearch').val());
		var $tb = $('#plResults tbody').empty();
		$.getJSON(base() + 'links/pharmacies', { q: q }).done(function (r) {
			if (!ok(r)) { note($('#plMsg'), refusal(r, t('ui.js.link.failed', 'Could not search. Please try again.')), true); return; }
			var rows = data(r) || [];
			if (!rows.length) {
				$tb.append($('<tr>').append($('<td colspan="2" class="text-muted">').text(t('ui.js.link.noneFound', 'No pharmacy accepting clinics has that name.'))));
				return;
			}
			rows.forEach(function (p) {
				var $tr = $('<tr>').attr('data-org', p.id);
				$tr.append($('<td>').text(p.name));
				$('<td>').append($('<button type="button" class="btn btn-xs btn-primary pl-request">').text(t('ui.js.link.request', 'Send link request'))
					.on('click', function () { requestLink(p); })).appendTo($tr);
				$tb.append($tr);
			});
		});
	}
	function requestLink(p) {
		confirmThen(t('ui.js.link.requestQ', 'Ask {0} to link?').replace('{0}', p.name),
			t('ui.js.link.requestMsg', 'Once {0} accepts, your doctors can send it prescriptions — patient name, phone and MRN, the medicines and the prescriber; never the diagnosis.').replace('{0}', p.name),
			t('ui.js.link.request', 'Send link request'), function () {
				$.post(base() + 'links', { pharmacyOrgId: p.id }).done(function (r) {
					if (ok(r)) note($('#plMsg'), t('ui.js.link.requested', 'Request sent.'), false);
					else note($('#plMsg'), refusal(r, t('ui.js.link.failed', 'Could not save. Please try again.')), true);
					$('#plResults tbody').empty();
					loadClinicLink();
				}).fail(function () { note($('#plMsg'), t('ui.js.link.failed', 'Could not save. Please try again.'), true); });
			});
	}

	$(function () {
		$(document).on('change', '#lcAccept', saveAccept);
		$(document).on('click', '#plFind', findPharmacies);
		$(document).on('keydown', '#plSearch', function (e) { if (e.key === 'Enter') { e.preventDefault(); findPharmacies(); } });
		$(document).on('click', '#plEnd', function () { if (current) endLink(current, $('#plMsg'), loadClinicLink); });
	});

	global.showLinkedClinics = showLinkedClinics;
	global.showPharmacyLink = showPharmacyLink;
})(window, window.jQuery);
