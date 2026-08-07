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
		['ppResults', 'ppNotices', 'ppMeetings', 'ppAttendance', 'ppDues', 'ppHomework'].forEach(function (p) {
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
		// Notices FIRST, and deliberately above the `current` guard: a notice is addressed to the guardian
		// or to the whole school, not to a child (3.5 D2). A guardian whose children are not yet placed —
		// or whose child list is still loading — must still see that the school is closed tomorrow.
		if (tab === 'notices') return loadNotices();
		// Meetings, like notices, are addressed to the GUARDIAN rather than to a child, so this sits
		// above the `current` guard too — a family with no child selected can still book.
		if (tab === 'meetings') return loadMeetings();
		if (!current) return;
		if (tab === 'results') return loadResults();
		if (tab === 'attendance') return loadAttendance();
		if (tab === 'dues') return loadDues();
		if (tab === 'homework') return loadHomework();
	}

	// ── school notices (slice 3.5) — NOT per-child ──────────────────────────────────────────────

	function loadNotices() {
		var $p = $('#ppNotices').empty();
		// No enrollNo: this read takes no child, and the server would ignore one if it were sent.
		get('portal/notices').done(function (res) {
			var rows = (res && res.collection) || [];
			if (!rows.length) {
				$p.append($('<div>').addClass('pp-card').append(emptyLine(t('ui.js.ppNoNotices'))));
				return;
			}
			rows.forEach(function (n) {
				// Server-ordered (pinned, then newest) and server-deduplicated across children. The client
				// re-sorting would be a second copy of a rule the server owns.
				var $card = $('<div>').addClass('pp-card');
				var $h = $('<h5>').text(esc(n.title));
				if (n.pinned) { $h.prepend($('<span>').addClass('pp-muted').text('📌 ')); }
				$card.append($h);
				$card.append($('<div>').addClass('pp-muted').text(esc(n.publishedOn)));
				// .text(), not .html(): a notice body is staff-authored free text sent to every family.
				$card.append($('<div>').css('white-space', 'pre-wrap').text(esc(n.body)));
				$p.append($card);
			});
		});
	}

	// ── meetings (slice edu-3.4) — the ONLY write on this surface ────────────────────────────────

	function loadMeetings() {
		var $p = $('#ppMeetings').empty();
		get('portal/meetings').done(function (res) {
			var ev = res && res.object;
			if (!ev || !ev.eventId) {
				// No open evening is normal — a school runs one or two a year.
				$p.append($('<div>').addClass('pp-card').append(emptyLine(t('ui.js.ppNoMeetings'))));
				return;
			}
			var $head = $('<div>').addClass('pp-card');
			$head.append($('<h5>').text(esc(ev.title)));
			if (ev.eventDate) { $head.append($('<div>').addClass('pp-muted').text(esc(ev.eventDate))); }
			if (ev.notes) { $head.append($('<div>').css('white-space','pre-wrap').text(esc(ev.notes))); }
			$p.append($head);

			var slots = ev.slots || [];
			if (!slots.length) {
				$p.append($('<div>').addClass('pp-card').append(emptyLine(t('ui.js.ppNoSlots'))));
				return;
			}
			var $card = $('<div>').addClass('pp-card');
			slots.forEach(function (s) {
				var free = Number(s.available) > 0;
				var $row = $('<div>').addClass('pp-row');
				$row.append($('<span>').text(esc(s.teacherName) + ' · ' + esc(timeOf(s.startsAt))));
				if (free) {
					// One button per slot, disabled the moment it is clicked: the server is idempotent per
					// (slot, guardian) so a double-click cannot double-book, but there is no reason to send
					// the second request at all.
					$row.append($('<button type="button" class="pp-tab">')
						.text(t('ui.js.ppBook'))
						.on('click', function () {
							var $b = $(this).prop('disabled', true);
							$.post(serverContext + 'portal/meetings/book', { slotId: s.slotId })
								.done(function (r) {
									// FAILED carries a reason a family can act on (slot taken, evening
									// closed) — shown rather than swallowed.
									if (!r || r.status !== 'SUCCESS') { uiAlert((r && r.message) || ''); }
									loadMeetings();
								})
								.fail(function () { $b.prop('disabled', false); });
						}));
				} else {
					$row.append($('<span>').addClass('pp-muted').text(t('ui.js.ppTaken')));
				}
				$card.append($row);
			});
			$p.append($card);
		});
	}

	/** "2026-12-01T18:00" -> "18:00". The date is on the heading; each row only needs its time. */
	function timeOf(iso) {
		if (!iso) { return ''; }
		var i = String(iso).indexOf('T');
		return i < 0 ? iso : String(iso).substring(i + 1, i + 6);
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
