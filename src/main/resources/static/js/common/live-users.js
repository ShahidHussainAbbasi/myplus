/* ============================================================================
 * live-users.js — fills the "users online" badge on the public landing and login
 * headers from GET /api/live-users.
 *
 * One implementation for both pages: they style the badge differently (a dark navy
 * nav bar vs a light card header) but the fetch/poll/format behaviour is identical,
 * so only the CSS is per-page.
 *
 * Markup contract — the container carries data-live-users and starts hidden, and the
 * number goes in a descendant carrying data-live-users-count:
 *
 *   <span class="nav-live" data-live-users hidden>
 *     <b data-live-users-count>0</b> online
 *   </span>
 *
 * The badge stays hidden until the count is a positive number, so a quiet moment or a
 * failed request shows nothing rather than "0 online" on a marketing page.
 *
 * NOTE: the figure served is deliberately inflated by app.live-users.multiplier
 * (see LiveUserCountService). Set that property to 1 to publish the true count.
 * ========================================================================== */
(function () {
	'use strict';

	var ENDPOINT = '/api/live-users';
	var POLL_MS = 60000;

	var timer = null;

	function targets() {
		return document.querySelectorAll('[data-live-users]');
	}

	function render(count) {
		var containers = targets();

		for (var i = 0; i < containers.length; i++) {
			var box = containers[i];
			var slot = box.querySelector('[data-live-users-count]');

			if (!(count > 0)) {
				box.hidden = true;
				continue;
			}

			// toLocaleString so a five-figure count reads as 12,480 rather than 12480.
			if (slot) { slot.textContent = Number(count).toLocaleString(); }
			box.hidden = false;
		}
	}

	function poll() {
		fetch(ENDPOINT, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
			.then(function (res) { return res.ok ? res.json() : null; })
			.then(function (data) { render(data ? data.count : 0); })
			// A marketing badge must never surface an error to a visitor — stay hidden and retry.
			.catch(function () { render(0); });
	}

	function start() {
		if (!targets().length) { return; }
		poll();
		stop();
		timer = setInterval(poll, POLL_MS);
	}

	function stop() {
		if (timer) { clearInterval(timer); timer = null; }
	}

	/* Don't poll a tab nobody is looking at — a landing page is often left open for hours. */
	document.addEventListener('visibilitychange', function () {
		if (document.hidden) { stop(); } else { start(); }
	});

	if (document.readyState === 'loading') {
		document.addEventListener('DOMContentLoaded', start);
	} else {
		start();
	}
})();
