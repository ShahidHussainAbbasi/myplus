/**
 * E5 — Platform access: what MaxTheService support did to THIS business, on the business's own screen.
 *
 * ── Why this is the half that matters ───────────────────────────────────────────────────────────
 * E4 built the record — every platform action is stamped against the customer's own organisation, with
 * who, why and when — and stopped there, because the customer-facing screen was E5's. A trail only the
 * platform can read is a filing cabinet, not accountability.
 *
 * ── ⭐ The customer can END a session, not merely read about one ────────────────────────────────
 * An access record the subject can see but not stop is a notice. The button says what it really does:
 * access ends when the operator's token next refreshes, inside fifteen minutes — not at the instant of the
 * click. Saying so is the difference between a limitation and a lie.
 *
 * ── Shared, deliberately ────────────────────────────────────────────────────────────────────────
 * Support sessions are opened over ANY tenant, so this renderer lives in /js/common and every dashboard
 * calls it with one line. A copy per module is the duplication the DRY rule names, and this is exactly the
 * kind of screen that would drift: four copies, one of which quietly stops offering "End it".
 */
(function (global) {
	'use strict';

	var $ = global.jQuery;

	function esc(s) {
		return (global.escHtml ? global.escHtml(String(s == null ? '' : s))
		                       : String(s == null ? '' : s));
	}

	function t(key, fallback) {
		var v = global.t ? global.t(key) : key;
		return (!v || v === key) ? fallback : v;
	}

	/** "4 Sept, 09:12" — enough to recognise, short enough to scan a list of them. */
	function when(iso) {
		if (!iso) return '';
		var d = new Date(String(iso));
		if (isNaN(d.getTime())) return '';
		return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
			+ ', ' + d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
	}

	function row(s) {
		var open = s.open === true;

		/*
		 * An OPEN session is the only thing on this card that needs acting on, so it is the only thing that
		 * carries controls — and it is marked with a live state rather than a date, because "is somebody in
		 * my books right now" is a different question from "who has been".
		 */
		var head = open
			? '<span class="sa-state sa-state--open">'
				+ esc(t('ui.js.accessOpenNow', 'Open now'))
				+ (s.expiresAt ? ' &middot; ' + esc(t('ui.js.until', 'until')) + ' ' + esc(when(s.expiresAt)) : '')
				+ '</span>'
			: '<span class="sa-state">' + esc(when(s.openedAt))
				+ (s.closedAt ? ' &rarr; ' + esc(when(s.closedAt)) : '') + '</span>';

		var mode = '<span class="sa-mode">'
			+ esc(s.writeApproved ? t('ui.js.changesAllowed', 'Changes allowed')
			                      : t('ui.js.readOnly', 'Read only'))
			+ '</span>';

		var actions = '';
		if (open) {
			actions = '<div class="sa-actions">'
				+ (s.writeApproved ? '' :
					'<button type="button" class="btn btn-default btn-xs js-sa-allow" data-id="' + esc(s.id) + '" '
					+ 'data-testid="allow-changes">'
					+ esc(t('ui.js.allowChanges', 'Allow changes')) + '</button>')
				+ '<button type="button" class="btn btn-default btn-xs js-sa-end" data-id="' + esc(s.id) + '" '
				+ 'data-testid="end-access">'
				+ esc(t('ui.js.endAccess', 'End it')) + '</button>'
				+ '</div>';
		}

		return '<li class="sa-row' + (open ? ' sa-row--open' : '') + '" data-testid="access-row">'
			+   '<div class="sa-main">'
			+     '<div class="sa-head">' + head + mode + '</div>'
			+     '<div class="sa-who">' + esc(s.operatorEmail || '') + '</div>'
			// Verbatim, and quoted: this is the sentence the operator typed about the customer's business,
			// and paraphrasing it would answer a different question than the one being asked.
			+     (s.reason ? '<div class="sa-reason">&ldquo;' + esc(s.reason) + '&rdquo;</div>' : '')
			+   '</div>'
			+   actions
			+ '</li>';
	}

	/**
	 * Draw the card into `container`.
	 *
	 * ⚠ Renders NOTHING when the business has never been accessed. An empty "Platform access" card on every
	 * shop's settings screen would teach people to ignore the heading, and the one time it matters is the one
	 * time they need to read it.
	 */
	global.renderPlatformAccess = function (container) {
		var $box = $(container);
		if (!$box.length) return;

		$.get(serverContext + 'getSupportSessions')
			.done(function (res) {
				if (!apiOk(res)) { $box.empty(); return; }
				var rows = (apiData(res) || {}).rows || [];
				if (!rows.length) { $box.empty(); return; }

				var anyOpen = rows.some(function (r) { return r.open === true; });
				$box.html('<section class="sa-card' + (anyOpen ? ' sa-card--live' : '') + '" '
					+     'data-testid="platform-access">'
					+   '<header class="sa-card__head">'
					+     '<h4>' + esc(t('ui.js.platformAccess', 'Platform access')) + '</h4>'
					+     '<span class="sa-card__n">' + rows.length + '</span>'
					+   '</header>'
					+   '<p class="sa-card__note">' + esc(t('ui.js.platformAccessNote',
							'When MaxTheService support opens your account, it is recorded here.')) + '</p>'
					+   '<ul class="sa-list">' + rows.map(row).join('') + '</ul>'
					+ '</section>');
			})
			// Silent on failure, deliberately: this card is supplementary to the settings screen it sits on,
			// and an error banner here would make a working Configuration page look broken.
			.fail(function () { $box.empty(); });
	};

	$(function () {
		$(document).on('click', '.js-sa-allow', function () {
			var id = $(this).attr('data-id');
			var $box = $(this).closest('[data-testid="platform-access"]').parent();
			global.uiConfirm({
				title: t('ui.js.allowChanges', 'Allow changes'),
				message: t('ui.js.allowChangesConfirm',
					'Support will be able to change your records until this session ends.')
			}).then(function (ok) {
				if (!ok) return;
				$.post(serverContext + 'approveSupportWrites', { id: id })
					.always(function () { global.renderPlatformAccess($box); });
			});
		});

		$(document).on('click', '.js-sa-end', function () {
			var id = $(this).attr('data-id');
			var $box = $(this).closest('[data-testid="platform-access"]').parent();
			/*
			 * ⚠ THE CONFIRMATION SAYS WHAT ACTUALLY HAPPENS.
			 *
			 * The scope is carried in the operator's token, so ending a session stops reaching this business
			 * when that token next refreshes — within fifteen minutes — not on the click. A dialog implying
			 * otherwise would be believed, and the one moment a customer presses this is the moment they most
			 * need to know what it does and does not do.
			 */
			global.uiConfirm({
				title: t('ui.js.endAccess', 'End it'),
				message: t('ui.js.endAccessConfirm',
					'Support access ends within 15 minutes. Everything done so far stays recorded below.')
			}).then(function (ok) {
				if (!ok) return;
				$.post(serverContext + 'endSupportSession', { id: id })
					.always(function () { global.renderPlatformAccess($box); });
			});
		});
	});
})(window);
