/*
 * HC-1b (2026-10-15) — the Clinic home's "Set up your clinic" checklist (owner / admin only; the block is not in the
 * DOM for anyone else). Each step ticks itself from REAL data, never from a flag somebody has to remember to set:
 *   doctors <- /clinic/doctors   at least one doctor registered
 *   staff   <- /team/users       at least one ACTIVE member who is neither the owner nor a doctor's linked login
 *   pharmacy <- /links           HC-1c: an ACTIVE link to a pharmacy (a request still waiting does not tick)
 * The list hides itself once every required step is done. The Today cards are /js/common/overview.js's.
 */
(function (global, $) {
	'use strict';
	if (!$) { return; }
	var base = global.serverContext || '/';

	function tick(step, done) {
		$('#chSetup [data-step="' + step + '"]').attr('data-done', done ? 'true' : 'false');
	}

	$(function () {
		var $setup = $('#chSetup');
		if (!$setup.length) { return; }
		$.when(
			$.getJSON(base + 'clinic/doctors'),
			$.getJSON(base + 'team/users'),
			$.getJSON(base + 'links')
		).done(function (dRes, tRes, lRes) {
			var doctors = (dRes[0] && dRes[0].success && dRes[0].data) || [];
			var members = (tRes[0] && tRes[0].data) || [];
			var doctorLogins = {};
			doctors.forEach(function (d) { if (d.linkedUserId) { doctorLogins[String(d.linkedUserId)] = true; } });
			var staff = members.filter(function (u) {
				return String(u.role || '').toUpperCase() !== 'OWNER'
					&& String(u.status || 'ACTIVE').toUpperCase() !== 'INACTIVE'
					&& !doctorLogins[String(u.userId || u.id)];
			});
			tick('doctors', doctors.length > 0);
			tick('staff', staff.length > 0);
			var linked = ((lRes[0] && lRes[0].success && lRes[0].data) || []).some(function (l) { return l.status === 'ACTIVE'; });
			tick('pharmacy', linked);
			var allDone = $setup.find('[data-step][data-required="true"]').toArray()
				.every(function (el) { return el.getAttribute('data-done') === 'true'; });
			$setup.toggle(!allDone).attr('data-loaded', 'true');
		});
		// A failed read leaves the list as drawn (nothing ticked) — it never ticks a step it could not check.
	});
})(window, window.jQuery);
