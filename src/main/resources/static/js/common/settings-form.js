/* ============================================================================
 * settings-form.js — the self-rendering Configuration screen, in ONE place.
 *
 * Business, education, welfare and agriculture each had their own near-identical
 * copy of this renderer, so every new setting TYPE had to be added four times and
 * the four drifted in the meantime. They now all call renderSettingsForm() with
 * their own endpoints; only the URLs and the container id differ.
 *
 * Renders from the service's settings catalog (SettingEntry): the screen has no
 * knowledge of individual settings, so adding one is a server-side change only.
 *
 * Supported types: BOOL (checkbox), SELECT (dropdown), INT / TEXT (input), MONEY (decimal input).
 * ========================================================================== */
(function (global) {
	'use strict';

	function esc(s) {
		return (global.escHtml ? global.escHtml(String(s == null ? '' : s))
		                       : String(s == null ? '' : s));
	}

	function fieldId(prefix, key) {
		// Setting keys contain dots ("org.locale.defaultLanguage") which are not safe in a selector.
		return prefix + '_' + String(key).replace(/[^A-Za-z0-9]/g, '_');
	}

	function controlFor(it, prefix, onChangeFn) {
		var id = fieldId(prefix, it.key);
		// E1 — a setting the tenant is not entitled to is DISABLED, not merely refused on click. The owner
		// should never meet a control that fails when they use it; the refusal still happens server-side and
		// the gate asserts both, because a disabled attribute is a courtesy and not a control.
		var common = ' id="' + esc(id) + '" data-key="' + esc(it.key) + '"'
		           + (it.locked ? ' disabled' : '')
		           + ' onchange="' + onChangeFn + '(this)"';

		if (it.type === 'SELECT') {
			var opts = (it.options || []).map(function (o) {
				var sel = String(o.value) === String(it.value) ? ' selected' : '';
				return '<option value="' + esc(o.value) + '"' + sel + '>' + esc(o.label) + '</option>';
			}).join('');
			return '<select class="form-control"' + common + '>' + opts + '</select>';
		}

		if (it.type === 'INT') {
			return '<input type="number" class="form-control" value="' + esc(it.value) + '"' + common + '/>';
		}

		// MONEY: a number input that ACCEPTS DECIMALS. Without step="any" the browser rejects 5.50 against the
		// default step of 1 — and an unknown type would fall through to the checkbox below, silently turning a
		// delivery fee into a tick box.
		if (it.type === 'MONEY') {
			return '<input type="number" step="any" min="0" class="form-control" value="'
				+ esc(it.value) + '"' + common + '/>';
		}

		if (it.type === 'TEXT') {
			return '<input type="text" class="form-control" value="' + esc(it.value) + '"' + common + '/>';
		}

		// BOOL (default)
		return '<input type="checkbox"' + (String(it.value) === 'true' ? ' checked' : '') + common + '/>';
	}

	/**
	 * Client-side filter over the rendered rows. Hides whole groups that end up empty, so the screen
	 * never shows a card heading with nothing under it, and reports the count so "3 of 41" is visible
	 * rather than the user wondering whether the list is short or filtered.
	 */
	function wireSearch($box, prefix, total) {
		var $input = $box.find('#' + prefix + '_search');
		if (!$input.length) return;              // short list — no toolbar was rendered
		var $count = $box.find('#' + prefix + '_count');
		var $empty = $box.find('.cfg-empty');

		function apply() {
			var q = String($input.val() || '').trim().toLowerCase();
			var shown = 0;
			$box.find('.cfg-group').each(function () {
				var $g = $(this), gShown = 0;
				$g.find('.cfg-row').each(function () {
					var hit = !q || (this.getAttribute('data-search') || '').indexOf(q) >= 0;
					this.style.display = hit ? '' : 'none';
					if (hit) { gShown++; shown++; }
				});
				$g.toggle(gShown > 0);
			});
			$count.text(q ? t('ui.js.nOfMSettings', shown, total) : t('ui.js.nSettings', total));
			$empty.toggle(shown === 0);
		}

		$input.on('input', apply);
		apply();
	}

	/**
	 * Confirm a save ON THE ROW that changed.
	 *
	 * A single banner at the top of the screen was adequate for six settings and is not for forty: the
	 * user toggles something near the bottom and the only confirmation renders off-screen, so the change
	 * looks like it did nothing. Callers keep their banner if they want it; this adds the local signal.
	 *
	 * @param el the control that changed (the same element passed to the onChange handler)
	 * @param ok whether the save succeeded
	 */
	global.markSettingSaved = function (el, ok) {
		if (!el || !el.id) return;
		var $pill = $('#' + el.id + '_saved');
		if (!$pill.length) return;
		$pill.text(ok ? t('ui.js.saved') : t('ui.js.saveFailed'))
			.toggleClass('is-err', !ok)
			.addClass('is-on');
		clearTimeout($pill.data('t'));
		// Long enough to notice, short enough not to accumulate a column of stale pills while an owner
		// works down the list.
		$pill.data('t', setTimeout(function () { $pill.removeClass('is-on'); }, 2400));
	};

	/**
	 * @param opts.container   selector of the div to render into (e.g. '#businessConfigBody')
	 * @param opts.loadUrl     GET endpoint returning {data:[SettingEntry+value]}
	 * @param opts.onChangeFn  NAME of the global save handler, called with the changed element
	 * @param opts.fieldPrefix short prefix for generated element ids
	 */
	global.renderSettingsForm = function (opts) {
		var $box = $(opts.container);
		$box.text(t('ui.js.loadingSettings'));

		$.get(serverContext + opts.loadUrl, function (res) {
			// Response shape differs by module: the commerce proxies return {data:[…]} while the
			// education proxy returns a GenericResponse, which carries lists in `collection`.
			// Accept both so one renderer serves every dashboard.
			var items = (res && (res.data || res.collection || res.object)) || [];
			if (!Array.isArray(items)) { items = []; }
			if (!items.length) {
				$box.html('<p style="color:#7a889c">' + esc(t('ui.js.noConfigurableSettings')) + '</p>');
				return;
			}

			var groups = {};
			items.forEach(function (it) { (groups[it.group] = groups[it.group] || []).push(it); });

			// A SETTINGS LIST, not a form. The old markup was one Bootstrap form-group per policy, which
			// reads fine at six settings and badly at forty: nothing separated one policy from the next,
			// and the help text sat under the CONTROL rather than under the label it explains.
			// Now: a card per group, a row per policy, label + explanation left, control right.
			var html = '';
			Object.keys(groups).forEach(function (g) {
				var rows = groups[g];
				html += '<section class="cfg-group">'
					+ '<header class="cfg-group__head"><h4>' + esc(g) + '</h4>'
					+ '<span class="cfg-group__n">' + rows.length + '</span></header>'
					+ '<div class="cfg-rows">';
				rows.forEach(function (it) {
					var id = fieldId(opts.fieldPrefix, it.key);
					// data-search carries everything worth matching, lower-cased once here so filtering is a
					// substring test rather than work repeated on every keystroke. The KEY is included: an
					// owner reading a support note looks up "pos.keyboard.shortcuts", not a prose label.
					var hay = ((it.label || '') + ' ' + (it.help || '') + ' ' + (it.key || '')).toLowerCase();
					/*
					 * E1 — the LOCKED row: a setting this tenant's plan does not include.
					 *
					 * The help text stays at full strength while the label dims. An owner looking at a locked
					 * row is deciding whether to ask for it, and hiding the row entirely would answer a
					 * question they never got to ask — the reason this is a lock rather than a filter.
					 *
					 * `lockedReason` is the SERVER's sentence, carried verbatim into the tooltip (standard 8d).
					 * The badge label is the only hard-coded copy, and it goes through ui.js.* like every other
					 * string JavaScript reads.
					 */
					var locked = !!it.locked;
					var badge = locked
						? '<span class="cfg-row__locked" title="' + esc(it.lockedReason || '') + '">'
							+ '<span class="glyphicon glyphicon-lock"></span> ' + esc(t('ui.js.notInPlan')) + '</span>'
						: '';
					html += '<div class="cfg-row' + (locked ? ' cfg-row--locked' : '') + '" data-search="' + esc(hay) + '">'
						+ '<div class="cfg-row__text">'
						+ '<label class="cfg-row__label" for="' + esc(id) + '">' + esc(it.label) + '</label>'
						+ '<span class="cfg-row__help">' + esc(it.help || '') + '</span>'
						+ '</div>'
						+ '<div class="cfg-row__control">' + controlFor(it, opts.fieldPrefix, opts.onChangeFn) + '</div>'
						+ badge
						+ '<span class="cfg-row__saved" id="' + esc(id) + '_saved"></span>'
						+ '</div>';
				});
				html += '</div></section>';
			});

			// Search only once the list is long enough to need it. Business Configuration has ~40 policies
			// and is unusable without a way in; Order settings has 7, and education/welfare/agriculture
			// fewer still — there a search box is a control that costs attention and saves none.
			var SEARCH_FROM = 12;
			var toolbar = items.length < SEARCH_FROM ? '' : ('<div class="cfg-toolbar">'
				+ '<div class="cfg-search"><span class="glyphicon glyphicon-search"></span>'
				+ '<input type="text" id="' + esc(opts.fieldPrefix) + '_search" autocomplete="off"'
				+ ' placeholder="' + esc(t('ui.js.searchSettings')) + '"></div>'
				+ '<span class="cfg-count" id="' + esc(opts.fieldPrefix) + '_count"></span>'
				+ '</div>');

			$box.html(toolbar + html + '<div class="cfg-empty" style="display:none">'
				+ esc(t('ui.js.noSettingsMatch')) + '</div>');
			wireSearch($box, opts.fieldPrefix, items.length);
		}, 'json').fail(function () {
			$box.html('<p style="color:#c0392b">' + esc(t('ui.js.couldNotLoadConfiguration')) + '</p>');
		});
	};

	/**
	 * Save one changed control. A checkbox reports .checked; everything else reports .value.
	 * @param reloadOnSave re-render after a successful save — needed when the change alters the
	 *                     page itself (the language setting), pointless otherwise.
	 */
	global.saveSettingsField = function (el, saveUrl, onDone) {
		var key = el.getAttribute('data-key');
		var value = (el.type === 'checkbox') ? (el.checked ? 'true' : 'false') : el.value;

		$.post(serverContext + saveUrl, { key: key, value: value }, function (res) {
			if (typeof onDone === 'function') { onDone(res && res.success, res); }
		}, 'json').fail(function () {
			if (typeof onDone === 'function') { onDone(false, null); }
		});
	};
})(window);
