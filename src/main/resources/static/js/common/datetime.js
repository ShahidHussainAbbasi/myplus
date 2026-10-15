/* ============================================================================================================
 * H6 — every time a person sees, in THEIR zone (docs/hms-phase1-design.md §4j). The model Salesforce, Shopify,
 * Stripe and Odoo use: an instant travels as UTC, and ONE formatter shows it in the viewer's zone with Intl.
 *
 * What the server sends today and what each function does with it:
 *   "2026-10-11T05:36:15Z" / "+05:00"  an instant with its offset       → shown in the viewer's zone
 *   "2026-10-11T05:36:15[.123456]"     zone-less: the services' JVMs     → read as UTC (verified: a 10:36 PKT
 *                                      hold true UTC                       prescription arrives as 05:36)
 *   "2026-10-11"                       a DATE (birthday, due date)        → returned as it is — never shifted
 *   anything else (server-formatted    not an ISO instant                 → returned as it is (the screen shows
 *   "11-10-2026 05:36")                                                    what the server wrote)
 *
 * Output is "yyyy-MM-dd HH:mm" in Latin digits — the shape every screen shows today, so a screen moving to the
 * formatter changes the ZONE, not its look. Nothing here reads a cookie or the server: the viewer's zone is the
 * browser's own (the same zone main.js already sends as myplus_tz).
 * ============================================================================================================ */
(function (global) {
	'use strict';

	var ISO_INSTANT = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:?\d{2})?$/;
	var DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

	function pad(n) { return (n < 10 ? '0' : '') + n; }

	/** The viewer's zone (IANA id), or undefined when the browser has no Intl — then the browser's own offset. */
	function viewerZone() {
		try { return Intl.DateTimeFormat().resolvedOptions().timeZone; } catch (e) { return undefined; }
	}

	/**
	 * An instant from the server as a Date, or null when the value is not an instant (a date, free text, empty).
	 * Zone-less ISO values are UTC — what the services hold.
	 */
	function serverInstant(v) {
		if (v === null || v === undefined || v === '') return null;
		if (v instanceof Date) return isNaN(v.getTime()) ? null : v;
		if (typeof v === 'number') return new Date(v);
		var s = String(v).trim();
		var m = ISO_INSTANT.exec(s);
		if (!m) return null;
		var ms = m[7] ? Number((m[7] + '00').slice(0, 3)) : 0;
		var utc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], m[6] ? +m[6] : 0, ms);
		if (m[8] && m[8] !== 'Z') {
			var sign = m[8].charAt(0) === '-' ? -1 : 1;
			var hh = +m[8].substr(1, 2), mm = +m[8].replace(':', '').substr(3, 2);
			utc -= sign * (hh * 60 + mm) * 60000;   // "10:36+05:00" is 05:36 UTC
		}
		return new Date(utc);
	}

	/** The parts of an instant in the viewer's zone (Latin digits, 24-hour). */
	function partsOf(d) {
		var zone = viewerZone();
		try {
			var f = new Intl.DateTimeFormat('en-GB', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
				hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });
			var o = {};
			f.formatToParts(d).forEach(function (p) { o[p.type] = p.value; });
			if (o.hour === '24') o.hour = '00';
			return o;
		} catch (e) {
			return { year: d.getFullYear(), month: pad(d.getMonth() + 1), day: pad(d.getDate()),
				hour: pad(d.getHours()), minute: pad(d.getMinutes()), second: pad(d.getSeconds()) };
		}
	}

	/** "yyyy-MM-dd HH:mm" in the viewer's zone; a date or non-instant is returned as it is. */
	global.fmtDateTime = function (v) {
		if (typeof v === 'string' && DATE_ONLY.test(v.trim())) return v.trim();
		var d = serverInstant(v);
		if (!d) return v == null ? '' : String(v);
		var p = partsOf(d);
		return p.year + '-' + p.month + '-' + p.day + ' ' + p.hour + ':' + p.minute;
	};

	/** "yyyy-MM-dd" — the viewer's DAY of an instant; a date is returned as it is (never shifted). */
	global.fmtDate = function (v) {
		if (typeof v === 'string' && DATE_ONLY.test(v.trim())) return v.trim();
		var d = serverInstant(v);
		if (!d) return v == null ? '' : String(v);
		var p = partsOf(d);
		return p.year + '-' + p.month + '-' + p.day;
	};

	/** "HH:mm" of an instant in the viewer's zone. */
	global.fmtTime = function (v) {
		var d = serverInstant(v);
		if (!d) return v == null ? '' : String(v);
		var p = partsOf(d);
		return p.hour + ':' + p.minute;
	};

	/**
	 * A time a PERSON typed in a datetime-local field ("2026-10-11T09:00", their wall clock) as the UTC instant the
	 * server stores ("2026-10-11T04:00:00Z" in Karachi). Used by phase C when a screen sends a typed time.
	 */
	global.typedToServer = function (localValue) {
		if (!localValue) return null;
		var m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(String(localValue).trim());
		if (!m) return null;
		var d = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], m[6] ? +m[6] : 0);   // the browser's own zone
		return d.toISOString().replace(/\.\d{3}Z$/, 'Z');
	};

	global.serverInstant = serverInstant;
	global.viewerZone = viewerZone;
})(window);
