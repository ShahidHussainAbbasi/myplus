/* ============================================================================
 * sessions.js — SESS-1. Shows the signed-in user how many devices their OWN
 * account is signed in on, and lets them sign the others out.
 *
 * WHY THIS EXISTS. Sessions are capped (jwt.max-sessions-per-user, 5) and the cap
 * evicts the OLDEST login to make room for a new one. A production shopkeeper lost
 * a sale to that on 2026-09-16: their till kept working for ~15 minutes and then
 * refused everything with "Invalid refresh token", with nothing on any screen to
 * say a slot had been taken. The cap stays (the owner's ruling); what changes is
 * that the number is visible and a slot can be freed deliberately.
 *
 * Markup contract — the chip starts hidden and carries the hooks:
 *
 *   <span data-sessions hidden>
 *     <b data-sessions-count>0</b> of <b data-sessions-max>5</b> devices
 *     <button type="button" data-sessions-signout>Sign out other devices</button>
 *   </span>
 *
 * data-at-cap="true" lands on the container when a new sign-in would evict the
 * oldest — that is the state worth styling, and what the gate asserts.
 *
 * ⚠ NOT the "users online" badge (live-users.js). That figure is everyone online
 * and is deliberately inflated by app.live-users.multiplier for the marketing
 * pages. This one is the signed-in user's own devices and is never multiplied —
 * two numbers that would be actively misleading if either were mistaken for the
 * other.
 * ========================================================================== */
(function () {
	'use strict';

	var READ = '/mySessions';
	var REVOKE = '/revokeOtherSessions';
	// Slow on purpose: a session count changes when somebody signs in, not second by second, and this runs
	// on every dashboard of every signed-in user.
	var POLL_MS = 120000;

	var timer = null;

	function chips() {
		return document.querySelectorAll('[data-sessions]');
	}

	function label(key, fallback) {
		return (typeof window.t === 'function' && window.t(key)) || fallback;
	}

	/** Paint every chip on the page from one answer. */
	function paint(info) {
		var count = Number(info && info.count);
		var max = Number(info && info.max);
		if (!(count > 0)) { hide(); return; }

		Array.prototype.forEach.call(chips(), function (chip) {
			var n = chip.querySelector('[data-sessions-count]');
			var m = chip.querySelector('[data-sessions-max]');
			if (n) n.textContent = String(count);
			if (m && max > 0) m.textContent = String(max);

			// At the cap, say what happens next — this sentence is the whole point of the slice.
			var atCap = max > 0 && count >= max;
			chip.setAttribute('data-at-cap', atCap ? 'true' : 'false');
			chip.setAttribute('title', atCap
				? label('ui.js.sessionsAtCap', 'Signing in on another device will end the oldest one.')
				: label('ui.js.sessionsHint', 'Devices this account is signed in on.'));

			// Nothing to sign out when this is the only device.
			var btn = chip.querySelector('[data-sessions-signout]');
			if (btn) btn.hidden = count < 2;

			chip.hidden = false;
		});
	}

	function hide() {
		Array.prototype.forEach.call(chips(), function (chip) { chip.hidden = true; });
	}

	function read() {
		if (!chips().length) return;
		// global:false — this is background work the user did not ask for, so it must not raise the
		// progress bar or the veil (the rule ajax-overlay.js records for bgJson).
		jQuery.ajax({ url: READ, dataType: 'json', global: false })
			.done(function (info) {
				// A failed or refused read shows NOTHING rather than a wrong number.
				if (!info || info.success === false) { hide(); return; }
				paint(info);
			})
			.fail(hide);
	}

	/** Sign out the other devices, after asking — never window.confirm (see confirm-dialog.js). */
	function signOutOthers() {
		var ask = (typeof window.uiConfirm === 'function')
			? window.uiConfirm({
				title: label('ui.js.sessionsSignOutTitle', 'Sign out other devices?'),
				message: label('ui.js.sessionsSignOutBody',
					'Every other device signed in as you will be signed out. This device stays signed in.'),
				confirmText: label('ui.js.sessionsSignOutConfirm', 'Sign out other devices')
			})
			: jQuery.Deferred().resolve(true).promise();

		jQuery.when(ask).then(function (ok) {
			if (ok !== true) return;
			jQuery.ajax({ url: REVOKE, type: 'POST', dataType: 'json' })
				.done(function (info) {
					if (!info || info.success === false) { return; }
					paint(info);   // the answer carries the fresh count, so no second read is needed
					if (typeof window.showSaleSuccess === 'function') {
						var n = Number(info.signedOut) || 0;
						window.showSaleSuccess(label('ui.js.sessionsSignedOut', 'Other devices signed out.')
							+ (n > 0 ? ' (' + n + ')' : ''));
					}
				});
		});
	}

	function start() {
		if (!chips().length) return;
		read();
		if (timer) { window.clearInterval(timer); }
		timer = window.setInterval(read, POLL_MS);
		// Delegated: the chip lives in a shared header fragment and may be re-rendered by a page that
		// replaces its navbar.
		jQuery(document).on('click', '[data-sessions-signout]', function (e) {
			e.preventDefault();
			signOutOthers();
		});
	}

	if (window.jQuery) {
		jQuery(start);
	}
})();
