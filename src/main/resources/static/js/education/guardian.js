/**
 * Slice 3.1 — the guardian portal page.
 * Design: microservices/docs/slices/edu-3.1-guardian-portal.md
 *
 * A SEPARATE script for a separate page. It deliberately shares nothing with education.js: that file drives
 * ~31 staff screens, and loading it here would put staff endpoints one console call away from a guardian's
 * session. The portal's surface is exactly the six reads below.
 *
 * The child list comes from the SERVER (`/portal/children`), derived from the guardian relationship on
 * every request. This file never sends a child id the server did not just give it — and even if it did,
 * the server intersects it again. Belt and braces, because the consequence is a stranger reading a
 * child's record.
 *
 * Everything is rendered with .text() — a child's name and a teacher's feedback are user data.
 */
(function () {
	'use strict';

	var current = null;   // the selected child's enrolment number

	function esc(s) { return s == null ? '' : String(s); }

	function get(path, params) {
		return $.get(serverContext + path, params || {});
	}

	function show(id) {
		['ppResults', 'ppAttendance', 'ppDues', 'ppHomework'].forEach(function (p) {
			$('#' + p).toggle(p === id);
		});
	}

	function emptyLine(text) {
		return $('<div>').addClass('pp-empty').text(text);
	}

	// ── boot ────────────────────────────────────────────────────────────────────────────────────

	$(function () {
		get('portal/me').done(function (res) {
			if (!res || res.status !== 'SUCCESS' || !res.object) { noAccess(); return; }
			$('#ppWho').text(esc(res.object.guardianName || res.object.email));
			loadChildren();
		}).fail(noAccess);

		$('.pp-tab[data-tab]').on('click', function () {
			$('.pp-tab[data-tab]').removeClass('on');
			$(this).addClass('on');
			var tab = this.getAttribute('data-tab');
			show('pp' + tab.charAt(0).toUpperCase() + tab.slice(1));
			loadTab(tab);
		});
	});

	function noAccess() {
		// One message for every failure: portal off, never invited, or revoked. Distinguishing them
		// would tell an outsider something about the school they are not entitled to know.
		$('#ppMain').hide();
		$('#ppNoAccess').show();
	}

	function loadChildren() {
		get('portal/children').done(function (res) {
			var kids = (res && res.collection) || [];
			if (!kids.length) { noAccess(); return; }

			var $wrap = $('#ppChildren').empty();
			kids.forEach(function (k, i) {
				var $b = $('<button type="button" class="pp-kid">')
					.text(esc(k.name) + (k.gradeName ? ' · ' + esc(k.gradeName) : ''))
					.on('click', function () {
						$('.pp-kid').removeClass('on');
						$(this).addClass('on');
						current = k.enrollNo;
						loadTab($('.pp-tab.on').attr('data-tab') || 'results');
					});
				if (i === 0) { $b.addClass('on'); current = k.enrollNo; }
				$wrap.append($b);
			});
			// A single child needs no switcher — chrome a guardian does not need is chrome in the way.
			$wrap.toggle(kids.length > 1);

			$('#ppMain').show();
			loadTab('results');
		}).fail(noAccess);
	}

	function loadTab(tab) {
		if (!current) return;
		if (tab === 'results') return loadResults();
		if (tab === 'attendance') return loadAttendance();
		if (tab === 'dues') return loadDues();
		if (tab === 'homework') return loadHomework();
	}

	// ── the four reads ──────────────────────────────────────────────────────────────────────────

	function loadResults() {
		get('portal/results', { enrollNo: current }).done(function (res) {
			var cards = (res && res.collection) || [];
			var $p = $('#ppResults').empty();
			if (!cards.length) {
				// Published cards only (D4). "None yet" is the honest answer when nothing is issued —
				// a draft result must never appear here.
				$p.append($('<div>').addClass('pp-card').append(emptyLine(t('ui.js.ppNoResults'))));
				return;
			}
			cards.forEach(function (c) {
				var $card = $('<div>').addClass('pp-card');
				$card.append($('<h5>').text(esc(c.termName)));
				$card.append($('<div>').addClass('pp-big')
					.text((c.termPercent == null ? '—' : c.termPercent + '%')
						+ (c.termGradeName ? ' · ' + esc(c.termGradeName) : '')));
				(c.rows || []).forEach(function (r) {
					$card.append($('<div>').addClass('pp-row')
						.append($('<span>').text(esc(r.subjectName)))
						.append($('<span>').text(
							(r.marksObtained == null ? '—' : r.marksObtained)
							+ (r.maxMarks == null ? '' : '/' + r.maxMarks)
							+ (r.grade ? ' · ' + esc(r.grade) : ''))));
				});
				if (c.issuedOn) {
					$card.append($('<div>').addClass('pp-sub pp-muted')
						.text(t('ui.js.ppIssued') + ' ' + esc(c.issuedOn)));
				}
				$p.append($card);
			});
		});
	}

	function loadAttendance() {
		get('portal/attendance', { enrollNo: current }).done(function (res) {
			var d = (res && res.object) || {};
			var $p = $('#ppAttendance').empty();
			var $card = $('<div>').addClass('pp-card');
			$card.append($('<h5>').text(t('ui.js.ppAttendanceTitle')));
			$card.append($('<div>').addClass('pp-big').text((d.rate == null ? 0 : d.rate) + '%'));
			$card.append($('<div>').addClass('pp-muted')
				.text(t('ui.js.ppPresentOf').replace('{p}', d.present || 0).replace('{t}', d.total || 0)));
			$p.append($card);

			if ((d.recent || []).length) {
				var $recent = $('<div>').addClass('pp-card');
				$recent.append($('<h5>').text(t('ui.js.ppRecent')));
				d.recent.forEach(function (r) {
					$recent.append($('<div>').addClass('pp-row')
						.append($('<span>').text(esc(r.date)))
						.append($('<span>').text(esc(r.status))));
				});
				$p.append($recent);
			}
		});
	}

	function loadDues() {
		get('portal/dues', { enrollNo: current }).done(function (res) {
			var d = (res && res.object) || {};
			var $p = $('#ppDues').empty();
			var $card = $('<div>').addClass('pp-card');
			$card.append($('<h5>').text(t('ui.js.ppOutstanding')));
			var $amount = $('<div>').addClass('pp-big').text(d.outstanding == null ? '0' : d.outstanding);
			if (d.outstanding > 0) $amount.addClass('pp-due');
			$card.append($amount);
			// No Pay button: paying is 3.2 and gated on the payment-provider decision (D-4). Showing the
			// balance is still an improvement on a guardian having no way to see it at all.
			$card.append($('<div>').addClass('pp-muted').text(t('ui.js.ppPayAtSchool')));
			$p.append($card);

			if ((d.rows || []).length) {
				var $hist = $('<div>').addClass('pp-card');
				$hist.append($('<h5>').text(t('ui.js.ppFeeHistory')));
				d.rows.forEach(function (r) {
					$hist.append($('<div>').addClass('pp-row')
						.append($('<span>').text(esc(r.paymentDate) || '—'))
						.append($('<span>').text(
							t('ui.js.ppPaid') + ' ' + (r.feePaid == null ? 0 : r.feePaid)
							+ (r.dueBalance ? ' · ' + t('ui.js.ppDue') + ' ' + r.dueBalance : ''))));
				});
				$p.append($hist);
			}
		});
	}

	function loadHomework() {
		get('portal/homework', { enrollNo: current }).done(function (res) {
			var tasks = (res && res.collection) || [];
			var $p = $('#ppHomework').empty();
			if (!tasks.length) {
				$p.append($('<div>').addClass('pp-card').append(emptyLine(t('ui.js.ppNoHomework'))));
				return;
			}
			var $card = $('<div>').addClass('pp-card');
			tasks.forEach(function (h) {
				var right = h.state
					? t('ui.js.ppHw' + h.state)
						+ (h.marksObtained == null ? '' : ' · ' + h.marksObtained
							+ (h.maxMarks == null ? '' : '/' + h.maxMarks))
					// A blank state means nothing has been recorded YET — not "did not do it" (2.4 D3).
					: t('ui.js.ppHwPending');
				$card.append($('<div>').addClass('pp-row')
					.append($('<span>').text(esc(h.title)
						+ (h.subjectName ? ' · ' + esc(h.subjectName) : '')
						+ (h.dueOn ? ' · ' + t('ui.js.ppDueOn') + ' ' + esc(h.dueOn) : '')))
					.append($('<span>').addClass('pp-muted').text(right)));
			});
			$p.append($card);
		});
	}
})();
