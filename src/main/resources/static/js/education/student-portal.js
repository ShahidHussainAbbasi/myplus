/**
 * Slice 3.3 — the student portal page.
 * Design: microservices/docs/slices/edu-3.3-student-portal.md
 *
 * A SEPARATE script for a separate page, for the reason 3.1 gave and which has not weakened: education.js
 * drives ~31 staff screens, and loading it here would put staff endpoints one console call away from an
 * external session. The portal's surface is exactly the five reads below.
 *
 * ── What differs from guardian.js, and why it is NOT a copy ──────────────────────────────────────────
 * There is no child picker and no `current` variable, because a student has one record (D2). Every request
 * below is a BARE path: this file cannot send an enrolment number, the proxy would not forward one, and the
 * service would not read one. Three layers agreeing costs nothing and removes the question.
 *
 * The two files share their rendering idiom but not their logic — the shared part (the actual reads) lives
 * server-side in PortalReadService, which is where duplication would have mattered. Merging these two into
 * one script parameterised by audience would put a guardian's child-switching code inside a student's
 * session, which is the opposite of what either page needs.
 *
 * Everything is rendered with .text() — a subject name, a room and a teacher's feedback are all user data.
 */
(function () {
	'use strict';

	var PANELS = ['spTimetable', 'spNotices', 'spResults', 'spHomework', 'spAttendance'];
	var DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

	function esc(s) { return s == null ? '' : String(s); }

	function get(path) {
		// No params argument, deliberately — see the header. There is nothing this page may ask FOR.
		return $.get(serverContext + path);
	}

	function show(id) {
		PANELS.forEach(function (p) { $('#' + p).toggle(p === id); });
	}

	function emptyLine(text) {
		return $('<div>').addClass('pp-empty').text(text);
	}

	function todayName() {
		// getDay(): 0 = Sunday. The server sends java.time.DayOfWeek names, which start at MONDAY.
		var js = new Date().getDay();
		return DAYS[(js + 6) % 7];
	}

	// ── boot ────────────────────────────────────────────────────────────────────────────────────

	$(function () {
		get('portal/my/me').done(function (res) {
			if (!res || res.status !== 'SUCCESS' || !res.object) { noAccess(); return; }
			$('#spWho').text(esc(res.object.name));
			$('#spMain').show();
			loadTab('timetable');          // the week is the landing tab — the ten-second visit's answer
		}).fail(noAccess);

		$('.pp-tab[data-tab]').on('click', function () {
			$('.pp-tab[data-tab]').removeClass('on');
			$(this).addClass('on');
			var tab = this.getAttribute('data-tab');
			show('sp' + tab.charAt(0).toUpperCase() + tab.slice(1));
			loadTab(tab);
		});
	});

	function noAccess() {
		// ONE message for every failure: portal off, students off, never invited, or revoked. Telling them
		// apart would disclose something about the school an outsider is not entitled to know.
		$('#spMain').hide();
		$('#spNoAccess').show();
	}

	function loadTab(tab) {
		if (tab === 'timetable') { return loadTimetable(); }
		if (tab === 'notices') { return loadNotices(); }
		if (tab === 'results') { return loadResults(); }
		if (tab === 'homework') { return loadHomework(); }
		if (tab === 'attendance') { return loadAttendance(); }
	}

	// ── my week ─────────────────────────────────────────────────────────────────────────────────

	function loadTimetable() {
		var $out = $('#spTimetable').empty();
		get('portal/my/timetable').done(function (res) {
			var rows = (res && res.collection) || [];
			if (!rows.length) { $out.append(emptyLine(t('ui.js.spNoTimetable'))); return; }

			// Grouped by day so the week reads as a week. The server already orders by day then period, so
			// this is a single pass — no sorting, no second request.
			var today = todayName();
			var byDay = {};
			rows.forEach(function (r) {
				var d = esc(r.dayOfWeek) || '—';
				(byDay[d] = byDay[d] || []).push(r);
			});

			DAYS.forEach(function (day) {
				if (!byDay[day]) { return; }
				var $card = $('<div>').addClass('pp-card');
				if (day === today) { $card.addClass('pp-today'); }
				$card.append($('<div>').addClass('pp-day').text(day.charAt(0) + day.slice(1).toLowerCase()));
				byDay[day].forEach(function (r) {
					var when = esc(r.periodName);
					if (r.startTime) { when += ' · ' + esc(r.startTime); }
					var what = esc(r.subjectName);
					if (r.room) { what += ' · ' + esc(r.room); }
					$card.append($('<div>').addClass('pp-row')
						.append($('<span>').text(when))
						.append($('<span>').addClass('pp-muted').text(what)));
				});
				$out.append($card);
			});
		}).fail(noAccess);
	}

	// ── school notices (slice 3.5) ──────────────────────────────────────────────────────────────

	function loadNotices() {
		var $out = $('#spNotices').empty();
		get('portal/my/notices').done(function (res) {
			var rows = (res && res.collection) || [];
			if (!rows.length) { $out.append(emptyLine(t('ui.js.spNoNotices'))); return; }
			rows.forEach(function (n) {
				// The server has already ordered these: pinned first, then newest. The client does not
				// re-sort — a rule duplicated in the browser drifts from the server that owns it.
				var $card = $('<div>').addClass('pp-card');
				var $h = $('<h5>').text(esc(n.title));
				if (n.pinned) { $h.prepend($('<span>').addClass('pp-muted').text('📌 ')); }
				$card.append($h);
				$card.append($('<div>').addClass('pp-muted').text(esc(n.publishedOn)));
				// .text(), not .html(): a notice body is staff-authored free text and reaches every family.
				$card.append($('<div>').css('white-space', 'pre-wrap').text(esc(n.body)));
				$out.append($card);
			});
		}).fail(noAccess);
	}

	// ── my results ──────────────────────────────────────────────────────────────────────────────

	function loadResults() {
		var $out = $('#spResults').empty();
		get('portal/my/results').done(function (res) {
			var cards = (res && res.collection) || [];
			if (!cards.length) { $out.append(emptyLine(t('ui.js.ppNoResults'))); return; }
			cards.forEach(function (c) {
				var $card = $('<div>').addClass('pp-card');
				$card.append($('<h5>').text(esc(c.termName)));
				$card.append($('<div>').addClass('pp-big')
					.text(esc(c.termPercent) + '%' + (c.termGradeName ? ' · ' + esc(c.termGradeName) : '')));
				(c.rows || []).forEach(function (r) {
					$card.append($('<div>').addClass('pp-row')
						.append($('<span>').text(esc(r.subjectName)))
						.append($('<span>').addClass('pp-muted')
							.text(esc(r.marksObtained) + '/' + esc(r.maxMarks)
								+ (r.grade ? ' · ' + esc(r.grade) : ''))));
				});
				$out.append($card);
			});
		}).fail(noAccess);
	}

	// ── my homework ─────────────────────────────────────────────────────────────────────────────

	function loadHomework() {
		var $out = $('#spHomework').empty();
		get('portal/my/homework').done(function (res) {
			var tasks = (res && res.collection) || [];
			if (!tasks.length) { $out.append(emptyLine(t('ui.js.ppNoHomework'))); return; }
			var $card = $('<div>').addClass('pp-card');
			tasks.forEach(function (h) {
				var right = esc(h.dueOn);
				if (h.state) { right += ' · ' + esc(h.state); }
				if (h.marksObtained != null) { right += ' · ' + esc(h.marksObtained) + '/' + esc(h.maxMarks); }
				$card.append($('<div>').addClass('pp-row')
					.append($('<span>').text(esc(h.title) + (h.subjectName ? ' · ' + esc(h.subjectName) : '')))
					.append($('<span>').addClass('pp-muted').text(right)));
			});
			$out.append($card);
		}).fail(noAccess);
	}

	// ── my attendance ───────────────────────────────────────────────────────────────────────────

	function loadAttendance() {
		var $out = $('#spAttendance').empty();
		get('portal/my/attendance').done(function (res) {
			var a = (res && res.object) || null;
			if (!a) { $out.append(emptyLine(t('ui.js.spNoAttendance'))); return; }
			var $card = $('<div>').addClass('pp-card');
			$card.append($('<div>').addClass('pp-big').text(esc(a.rate) + '%'));
			$card.append($('<div>').addClass('pp-muted')
				.text(esc(a.present) + ' / ' + esc(a.total)));
			(a.recent || []).forEach(function (d) {
				$card.append($('<div>').addClass('pp-row')
					.append($('<span>').text(esc(d.date)))
					.append($('<span>').addClass('pp-muted').text(esc(d.status))));
			});
			$out.append($card);
		}).fail(noAccess);
	}
})();
