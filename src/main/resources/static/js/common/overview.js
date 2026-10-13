/*
 * RW-4b (2026-10-13) — the owner's overview: fills the number cards the page drew. Which cards exist is the server's
 * decision (ui_menu rows overview.*); this asks only for the figures of cards that are THERE, in parallel, once.
 *   tokens / waiting / doctors  ← /clinic/doctors        (each doctor's tokens today, waiting now, not-today)
 *   prescriptions               ← /prescriptionsWaitingCount (pending + partly dispensed, the caller's branches)
 *   takings                     ← /getBusinessDashboardStats (todayRevenue / todaySales — only for report.view)
 * A figure that cannot be had shows "—", never 0: "we could not ask" is not "nothing happened".
 */
(function (global, $) {
	'use strict';
	if (!$) { return; }
	var base = global.serverContext || '/';
	function set(id, v) { $('#' + id).text(v === undefined || v === null || v === '' ? '—' : v); }
	function count(n) { return Number(n || 0).toLocaleString(); }

	$(function () {
		try { $('#ovDate').text(new Date().toLocaleDateString(document.documentElement.lang || undefined,
			{ weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' })); } catch (e) { /* old browser */ }

		if ($('#ovTokens, #ovWaiting, #ovDoctors').length) {
			$.getJSON(base + 'clinic/doctors').done(function (r) {
				var ds = (r && r.success && r.data) || null;
				if (!ds) { return; }
				var issued = 0, waiting = 0, onDuty = 0;
				ds.forEach(function (d) {
					issued += Number(d.issuedToday || 0);
					waiting += Number(d.waitingNow || 0);
					if (!d.closedToday) { onDuty += 1; }
				});
				set('ovTokens', count(issued));
				set('ovWaiting', count(waiting));
				set('ovDoctors', count(onDuty));
				$('#ovDoctorsOf').text(onDuty + ' / ' + ds.length);
			});
		}
		if ($('#ovRx').length) {
			$.getJSON(base + 'prescriptionsWaitingCount').done(function (r) {
				if (r && r.success && r.data !== undefined) { set('ovRx', count(r.data)); }
			});
		}
		if ($('#ovTakings').length) {
			$.getJSON(base + 'getBusinessDashboardStats').done(function (r) {
				var s = r && r.status === 'SUCCESS' ? r.object : null;
				if (!s || s.todayRevenue === undefined) { return; }
				set('ovTakings', typeof global.money === 'function' ? global.money(s.todayRevenue) : s.todayRevenue);
				var $s = $('#ovSales');   // "{0} sales" in the person's language (ui.ov.sales)
				$s.text(String($s.attr('data-tpl') || '{0}').replace('{0}', count(s.todaySales)));
			});
		}
	});
})(window, window.jQuery);
