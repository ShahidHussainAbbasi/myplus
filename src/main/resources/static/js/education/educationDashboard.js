/*
 * Education owner analytics dashboard (slice 22).
 * One call to /getDashboardAnalytics returns KPI headlines + chart-ready series across
 * four lenses (Finance, Students, Attendance, Staff). Rendered with Chart.js.
 */
(function () {
	var CHARTS = {};
	var FIN = '#1565C0', FIN_DUE = '#cbd5e1', STU = '#7C3AED', ATT = '#0E7490', STAFF = '#B45309';
	var PIE = ['#1565C0', '#7C3AED', '#0E7490', '#B45309', '#15803d', '#D97706', '#be185d', '#0891b2', '#4338CA', '#64748b'];

	var KPIS = [
		{ k: 'totalStudents', l: 'Students', icon: 'glyphicon-education', accent: 'stu',
		  sub: function (o) { return '<span class="up">+' + n(o.freshStudents) + '</span> enrolled this year'; } },
		{ k: 'activeStudents', l: 'Active students', icon: 'glyphicon-ok-circle', accent: 'stu' },
		{ k: 'totalStaff', l: 'Staff', icon: 'glyphicon-briefcase', accent: 'staff',
		  sub: function (o) { return '1 : ' + n(o.studentTeacherRatio) + ' student‑teacher'; } },
		{ k: 'collectedThisMonth', l: 'Collected (this month)', icon: 'glyphicon-usd', accent: 'fin', fmt: money },
		{ k: 'collectedTotal', l: 'Collected (total)', icon: 'glyphicon-stats', accent: 'fin', fmt: money },
		{ k: 'outstanding', l: 'Outstanding dues', icon: 'glyphicon-warning-sign', accent: 'fin', fmt: money },
		{ k: 'collectionRate', l: 'Collection rate', icon: 'glyphicon-signal', accent: 'fin', fmt: pct },
		{ k: 'attendanceRate', l: 'Attendance rate', icon: 'glyphicon-check', accent: 'att', fmt: pct }
	];

	ready(function () {
		var btn = document.getElementById('anRefresh');
		if (btn) btn.addEventListener('click', loadAnalytics);
		if (document.getElementById('DashboardDiv')) loadAnalytics();
	});

	function loadAnalytics() {
		$.ajax({ type: 'GET', url: serverContext + 'getDashboardAnalytics' })
			.then(function (data) {
				if (data && data.status === 'SUCCESS') { hideErr(); render(data.object || {}); }
				else { showErr(data && data.message); }
			})
			.fail(function () { showErr('Could not load analytics. Please try again.'); });
	}

	function render(o) {
		renderKpis(o.kpis || {});

		// ---- Finance ----
		bars('chFeeTrend', o.feeTrend, [
			{ label: 'Collected', data: (o.feeTrend || {}).collected, backgroundColor: FIN },
			{ label: 'Due', data: (o.feeTrend || {}).due, backgroundColor: FIN_DUE }
		], { stacked: false, money: true, legend: true }, hasAny((o.feeTrend || {}).collected) || hasAny((o.feeTrend || {}).due));
		doughnut('chPayModes', o.paymentModes, { money: true });
		bar('chCollClass', o.collectionByClass, FIN, { money: true });

		// ---- Students ----
		line('chEnrollTrend', o.enrollTrend, STU);
		bar('chStudentsClass', o.studentsByClass, STU, {});
		doughnut('chGender', o.genderSplit, {});
		doughnut('chStatus', o.studentStatus, {});

		// ---- Attendance ----
		line('chAttTrend', o.attendanceTrend, ATT, { max100: true, suffix: '%' });
		bar('chAttClass', o.attendanceByClass, ATT, { max100: true, suffix: '%' });

		// ---- Staff ----
		bar('chStaffDesig', o.staffByDesignation, STAFF, {});
	}

	function renderKpis(k) {
		var box = document.getElementById('anKpis');
		if (!box) return;
		box.innerHTML = KPIS.map(function (c) {
			var raw = k[c.k];
			var val = c.fmt ? c.fmt(raw) : n(raw);
			var sub = c.sub ? c.sub(k) : '';
			return '<div class="an-kpi accent-' + c.accent + '">' +
				'<div class="an-kpi-l"><span class="glyphicon ' + c.icon + '"></span>' + c.l + '</div>' +
				'<div class="an-kpi-v">' + val + '</div>' +
				(sub ? '<div class="an-kpi-sub">' + sub + '</div>' : '') +
				'</div>';
		}).join('');
	}

	// ---- chart builders ---------------------------------------------------
	function bar(id, s, color, opt) {
		s = s || {}; opt = opt || {};
		if (!setEmpty(id, !hasAny(s.data))) return;
		mk(id, { type: 'bar',
			data: { labels: s.labels || [], datasets: [{ data: s.data || [], backgroundColor: color, borderRadius: 5, maxBarThickness: 46 }] },
			options: baseOpts(opt, false) });
	}
	function bars(id, s, datasets, opt, has) {
		s = s || {};
		if (!setEmpty(id, !has)) return;
		datasets.forEach(function (d) { d.borderRadius = 5; d.maxBarThickness = 30; });
		mk(id, { type: 'bar', data: { labels: s.labels || [], datasets: datasets }, options: baseOpts(opt, opt.legend) });
	}
	function line(id, s, color, opt) {
		s = s || {}; opt = opt || {};
		if (!setEmpty(id, !hasAny(s.data))) return;
		mk(id, { type: 'line',
			data: { labels: s.labels || [], datasets: [{ data: s.data || [], borderColor: color, backgroundColor: hexA(color, 0.12),
				fill: true, tension: 0.35, pointRadius: 2, pointHoverRadius: 5, borderWidth: 2 }] },
			options: baseOpts(opt, false) });
	}
	function doughnut(id, s, opt) {
		s = s || {}; opt = opt || {};
		if (!setEmpty(id, !hasAny(s.data))) return;
		mk(id, { type: 'doughnut',
			data: { labels: s.labels || [], datasets: [{ data: s.data || [], backgroundColor: PIE, borderWidth: 2, borderColor: '#fff' }] },
			options: { responsive: true, maintainAspectRatio: false, cutout: '60%',
				plugins: { legend: { position: 'bottom', labels: { boxWidth: 12, font: { size: 11 } } },
					tooltip: { callbacks: { label: tipLabel(opt) } } } } });
	}

	function baseOpts(opt, legend) {
		var yMax = opt.max100 ? { max: 100 } : {};
		return {
			responsive: true, maintainAspectRatio: false,
			plugins: { legend: { display: !!legend, position: 'bottom', labels: { boxWidth: 12, font: { size: 11 } } },
				tooltip: { callbacks: { label: tipLabel(opt) } } },
			scales: {
				x: { grid: { display: false }, ticks: { font: { size: 11 } } },
				y: Object.assign({ beginAtZero: true, grid: { color: '#eef2f7' }, ticks: { font: { size: 11 },
					callback: function (v) { return opt.money ? money(v) : (opt.suffix ? v + opt.suffix : v); } } }, yMax)
			}
		};
	}
	function tipLabel(opt) {
		return function (ctx) {
			var lbl = ctx.dataset.label ? ctx.dataset.label + ': ' : '';
			var v = ctx.parsed.y != null ? ctx.parsed.y : ctx.parsed;
			return lbl + (opt.money ? money(v) : (opt.suffix ? v + opt.suffix : n(v)));
		};
	}

	function mk(id, config) {
		destroy(id);
		var el = document.getElementById(id);
		if (!el || typeof Chart === 'undefined') return;
		CHARTS[id] = new Chart(el.getContext('2d'), config);
	}
	function destroy(id) { if (CHARTS[id]) { CHARTS[id].destroy(); delete CHARTS[id]; } }

	/** Toggle a "no data yet" overlay; returns true when there IS data (caller should draw). */
	function setEmpty(id, isEmpty) {
		var el = document.getElementById(id);
		if (!el) return false;
		var wrap = el.parentNode, ov = wrap.querySelector('.an-empty');
		if (isEmpty) {
			destroy(id);
			el.style.display = 'none';
			if (!ov) { ov = document.createElement('div'); ov.className = 'an-empty'; ov.textContent = 'No data yet'; wrap.appendChild(ov); }
			return false;
		}
		el.style.display = '';
		if (ov) ov.remove();
		return true;
	}

	// ---- helpers ----------------------------------------------------------
	function hasAny(arr) { return Array.isArray(arr) && arr.some(function (v) { return Number(v) > 0; }); }
	function n(v) { return (v == null || isNaN(v)) ? 0 : v; }
	function money(v) { return Number(n(v)).toLocaleString(); }
	function pct(v) { return n(v) + '%'; }
	function hexA(hex, a) {
		var h = hex.replace('#', ''); var r = parseInt(h.substring(0, 2), 16), g = parseInt(h.substring(2, 4), 16), b = parseInt(h.substring(4, 6), 16);
		return 'rgba(' + r + ',' + g + ',' + b + ',' + a + ')';
	}
	function showErr(msg) { var e = document.getElementById('anError'); if (e) { e.textContent = msg || 'Error loading analytics.'; e.style.display = 'block'; } }
	function hideErr() { var e = document.getElementById('anError'); if (e) e.style.display = 'none'; }
	function ready(fn) { if (document.readyState !== 'loading') fn(); else document.addEventListener('DOMContentLoaded', fn); }
})();
