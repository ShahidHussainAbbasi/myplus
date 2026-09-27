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
			// data-previous lets a handler revert a cancelled or refused change. A dropdown left showing a
			// value that was never saved is how an owner comes to believe their shop is configured one way
			// while the server behaves another.
			return '<select class="form-control" data-previous="' + esc(it.value) + '"' + common + '>'
				+ opts + '</select>';
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
		/*
		 * MULTILINE \u2014 a terms block, not a label.
		 *
		 * dir="auto" so an owner writing Urdu types right-to-left in the box itself, matching how it will
		 * print. maxlength 500 mirrors the storage column, so the limit is met while typing instead of as a
		 * database error on save.
		 *
		 * \u26a0 The value goes in the ELEMENT BODY, never a value="" attribute \u2014 a textarea has no value
		 * attribute, and writing one renders an empty box that silently discards what the owner saved.
		 */
		if (it.type === 'MULTILINE') {
			return '<textarea class="form-control" rows="3" maxlength="500" dir="auto"'
				+ common + '>' + esc(it.value) + '</textarea>';
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

		/*
		 * UI-CFG-1 — with a category rail, search spans EVERY category (an owner searching "receipt" must not first
		 * guess which category holds it) and the rail shows each category's match count. With the box empty, only
		 * the active category shows.
		 */
		function apply() {
			var q = String($input.val() || '').trim().toLowerCase();
			var active = $box.attr('data-active-cat');
			var hasRail = $box.find('.cfg-rail').length > 0;
			var shown = 0, perCat = {};
			$box.find('.cfg-group').each(function () {
				var $g = $(this), gShown = 0, cat = $g.attr('data-cat');
				$g.find('.cfg-row').each(function () {
					var hit = !q || (this.getAttribute('data-search') || '').indexOf(q) >= 0;
					this.style.display = hit ? '' : 'none';
					if (hit) gShown++;
				});
				var inScope = !hasRail || !!q || cat === active;
				$g.toggle(gShown > 0 && inScope);
				if (inScope) shown += gShown;
				perCat[cat] = (perCat[cat] || 0) + gShown;
			});
			$box.toggleClass('cfg-searching', !!q);
			$box.find('.cfg-rail__item').each(function () {
				var c = this.getAttribute('data-cat');
				$(this).find('.cfg-rail__hits').text(q ? String(perCat[c] || 0) : '');
				$(this).toggleClass('is-empty', !!q && !perCat[c]);
			});
			$count.text(q ? t('ui.js.nOfMSettings', shown, total) : t('ui.js.nSettings', total));
			$empty.toggle(shown === 0);
		}

		$input.on('input', apply);
		$box.data('cfgApply', apply);
		apply();
	}

	/** The chosen category, per screen, for the life of the page — a re-render after a save stays where it was. */
	var ACTIVE_CAT = {};

	function activateCategory($box, cat) {
		$box.attr('data-active-cat', cat);
		ACTIVE_CAT[$box.attr('id') || 'cfg'] = cat;
		$box.find('.cfg-rail__item').each(function () {
			var on = this.getAttribute('data-cat') === cat;
			this.classList.toggle('is-active', on);
			this.setAttribute('aria-selected', on ? 'true' : 'false');
			this.setAttribute('tabindex', on ? '0' : '-1');
		});
		$box.find('.cfg-pane__title').text($box.find('.cfg-rail__item[data-cat="' + cat + '"] .cfg-rail__label').text());
		var activeTab = $box.find('.cfg-rail__item[data-cat="' + cat + '"]').attr('id');
		if (activeTab) $box.find('.cfg-pane').attr('aria-labelledby', activeTab);
		var apply = $box.data('cfgApply');
		if (apply) { apply(); }
		else { $box.find('.cfg-group').each(function () { $(this).toggle(this.getAttribute('data-cat') === cat); }); }
	}

	/**
	 * Open the category holding a setting and bring its row into view — for links from other screens ("set up tax")
	 * and for tests. Returns false when the setting is not on this page.
	 */
	global.revealSetting = function (key) {
		var el = document.querySelector('[data-key="' + String(key).replace(/"/g, '') + '"]');
		if (!el) return false;
		var $box = $(el).closest('.cfg-layout').parent();
		if (!$box.length) return true;                    // no rail on this screen — nothing to switch
		var $input = $box.find('.cfg-search input');
		if ($input.val()) { $input.val(''); }
		var cat = $(el).closest('.cfg-group').attr('data-cat');
		if (cat) activateCategory($box, cat);
		var row = $(el).closest('.cfg-row')[0];
		if (row && row.scrollIntoView) row.scrollIntoView({ block: 'center' });
		return true;
	};

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
	 * @param opts.categories  optional [{id, label, groups:[group names]}] — renders the category rail (UI-CFG-1)
	 * @param opts.resetFn     optional NAME of the global handler for "Reset to default", called with the button
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

			/*
			 * UI-CFG-1 — optional CATEGORY RAIL (opts.categories: [{id, label, groups: [group names]}]). The Shopify /
			 * Square pattern: a few categories an owner recognises, one pane at a time, search across all. A group no
			 * category names lands in "Other", so a setting added on the server can never go missing. Without
			 * opts.categories the screen renders exactly as before.
			 */
			var cats = null, catOf = {};
			if (opts.categories && opts.categories.length) {
				cats = opts.categories.map(function (c) { return { id: c.id, label: c.label, groups: [], n: 0, changed: 0 }; });
				Object.keys(groups).forEach(function (g) {
					var hit = null;
					opts.categories.forEach(function (c, i) { if (!hit && c.groups.indexOf(g) >= 0) hit = cats[i]; });
					if (!hit) {
						hit = cats.filter(function (c) { return c.id === 'other'; })[0];
						if (!hit) { hit = { id: 'other', label: t('ui.js.cfgCatOther'), groups: [], n: 0, changed: 0 }; cats.push(hit); }
					}
					hit.groups.push(g);
					hit.n += groups[g].length;
					hit.changed += groups[g].filter(function (it) { return it.isDefault === false; }).length;
					catOf[g] = hit.id;
				});
				cats = cats.filter(function (c) { return c.n > 0; });   // nothing in it for this tenant: not offered
			}

			// A SETTINGS LIST, not a form. The old markup was one Bootstrap form-group per policy, which
			// reads fine at six settings and badly at forty: nothing separated one policy from the next,
			// and the help text sat under the CONTROL rather than under the label it explains.
			// Now: a card per group, a row per policy, label + explanation left, control right.
			var html = '';
			var order = cats ? [].concat.apply([], cats.map(function (c) { return c.groups; })) : Object.keys(groups);
			order.forEach(function (g) {
				var rows = groups[g];
				html += '<section class="cfg-group"' + (cats ? ' data-cat="' + esc(catOf[g]) + '"' : '') + '>'
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
					// SET-CERT F3 — name the KIND of lock. Every lock used to say "Not in plan", including the opening-
					// balance cutover date, which is locked for an accounting reason. The plan guard's sentence is the
					// server's own ("…not included in your current plan…"); anything else is a plain "Locked" and keeps
					// its real reason in the tooltip.
					var byPlan = /current plan/i.test(it.lockedReason || '');
					var badge = locked
						? '<span class="cfg-row__locked" data-lock="' + (byPlan ? 'plan' : 'other') + '" title="' + esc(it.lockedReason || '') + '">'
							+ '<span class="glyphicon glyphicon-lock"></span> ' + esc(t(byPlan ? 'ui.js.notInPlan' : 'ui.js.settingLocked')) + '</span>'
						: '';
					/*
					 * UI-CFG-1 — CHANGED FROM THE DEFAULT, and a way back. The owner sees at a glance what they set
					 * themselves, and "Reset to default" REMOVES the override (a saved value equal to the default is not
					 * the same: it pins the setting against the shop preset and the business type).
					 */
					var changed = it.isDefault === false;
					var dflt = it.type === 'BOOL' ? t(String(it.defaultValue) === 'true' ? 'ui.js.cfgOn' : 'ui.js.cfgOff')
						: ((it.defaultValue === '' || it.defaultValue == null) ? t('ui.js.cfgBlank') : String(it.defaultValue));
					var mark = changed ? ' <span class="cfg-row__changed" title="' + esc(t('ui.js.cfgChangedFrom', dflt)) + '"></span>' : '';
					var reset = (changed && !locked && opts.resetFn)
						? '<button type="button" class="cfg-row__reset" data-reset-key="' + esc(it.key) + '" onclick="' + opts.resetFn + '(this)"'
							+ ' title="' + esc(t('ui.js.cfgResetTitle', dflt)) + '">' + esc(t('ui.js.cfgReset')) + '</button>'
						: '';
					html += '<div class="cfg-row' + (locked ? ' cfg-row--locked' : '') + (changed ? ' cfg-row--changed' : '') + '" data-search="' + esc(hay) + '">'
						+ '<div class="cfg-row__text">'
						+ '<label class="cfg-row__label" for="' + esc(id) + '">' + esc(it.label) + mark + '</label>'
						+ '<span class="cfg-row__help">' + esc(it.help || '') + '</span>'
						+ reset
						+ '</div>'
						+ '<div class="cfg-row__control">' + controlFor(it, opts.fieldPrefix, opts.onChangeFn) + '</div>'
						+ badge
						// SET-GUIDE a11y — a live region, so "Saved" / "Save failed" is ANNOUNCED, not only shown.
						+ '<span class="cfg-row__saved" id="' + esc(id) + '_saved" role="status" aria-live="polite"></span>'
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

			var body = toolbar + html + '<div class="cfg-empty" style="display:none">' + esc(t('ui.js.noSettingsMatch')) + '</div>';
			if (cats) {
				var rail = '<nav class="cfg-rail" role="tablist" aria-orientation="vertical" aria-label="' + esc(t('ui.js.cfgCategories')) + '">'
					+ cats.map(function (c) {
						return '<button type="button" role="tab" class="cfg-rail__item" data-cat="' + esc(c.id) + '" id="' + esc(opts.fieldPrefix) + '_cat_' + esc(c.id) + '"'
							+ ' aria-controls="' + esc(opts.fieldPrefix) + '_pane">'
							+ '<span class="cfg-rail__label">' + esc(c.label) + '</span>'
							+ '<span class="cfg-rail__meta"><span class="cfg-rail__hits"></span>'
							+ (c.changed ? '<span class="cfg-rail__changed" title="' + esc(t('ui.js.cfgNChanged', c.changed)) + '">' + c.changed + '</span>' : '')
							+ '<span class="cfg-rail__n">' + c.n + '</span></span></button>';
					}).join('') + '</nav>';
				// SET-GUIDE a11y — the WAI-ARIA tabs pattern in full: the pane is the tabpanel the tabs control, labelled
				// by whichever tab is active (activateCategory keeps aria-labelledby current).
				body = '<div class="cfg-layout">' + rail + '<div class="cfg-pane" role="tabpanel" id="' + esc(opts.fieldPrefix) + '_pane">'
					+ '<h3 class="cfg-pane__title"></h3>' + body + '</div></div>';
			}
			$box.html(body);
			wireSearch($box, opts.fieldPrefix, items.length);
			if (cats) {
				var remembered = ACTIVE_CAT[$box.attr('id') || 'cfg'];
				var first = cats.some(function (c) { return c.id === remembered; }) ? remembered : cats[0].id;
				activateCategory($box, first);
				$box.find('.cfg-rail').on('click', '.cfg-rail__item', function () {
					var $in = $box.find('.cfg-search input');
					if ($in.val()) $in.val('');
					activateCategory($box, this.getAttribute('data-cat'));
				}).on('keydown', '.cfg-rail__item', function (e) {
					// WAI-ARIA tabs (vertical): arrows move between categories, Home/End jump to the ends.
					var list = $box.find('.cfg-rail__item').toArray(), i = list.indexOf(this), next = null;
					if (e.key === 'ArrowDown') next = list[(i + 1) % list.length];
					if (e.key === 'ArrowUp') next = list[(i - 1 + list.length) % list.length];
					if (e.key === 'Home') next = list[0];
					if (e.key === 'End') next = list[list.length - 1];
					if (next) { e.preventDefault(); next.focus(); next.click(); }
				});
			}
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
			if (typeof onDone === 'function') { onDone(settingsOk(res), res); }
		}, 'json').fail(function (xhr) {
			if (typeof onDone === 'function') { onDone(false, xhr && xhr.responseJSON); }
		});
	};

	/**
	 * Both envelopes: the commerce services answer {success:true}; education / welfare / agriculture answer a
	 * GenericResponse {status:"SUCCESS"} (which also carries success). One reading, so no screen gets it wrong.
	 */
	function settingsOk(res) {
		return !!res && (res.success === true || res.status === 'SUCCESS');
	}

	/**
	 * SET-GUIDE — "Reset to default" for any settings screen: POST the key to the screen's reset route, which REMOVES
	 * the override (not a save of the default, which would pin it against presets). The caller re-renders.
	 */
	global.resetSettingsField = function (btn, resetUrl, onDone) {
		var key = btn.getAttribute('data-reset-key');
		btn.disabled = true;
		$.post(serverContext + resetUrl, { key: key }, function (res) {
			var ok = settingsOk(res);
			if (!ok) btn.disabled = false;
			if (typeof onDone === 'function') { onDone(ok, res); }
		}, 'json').fail(function (xhr) {
			btn.disabled = false;
			if (typeof onDone === 'function') { onDone(false, xhr && xhr.responseJSON); }
		});
	};

	/**
	 * SET-GUIDE — the one save/reset outcome every settings screen shows: the row's own marker (announced — it is a
	 * live region), the screen's banner with the server's sentence, and on a refusal a re-read, so a refused value
	 * does not stay in the box pretending to be in force. `reload` re-renders the screen.
	 */
	global.settingsOutcome = function (el, msgSel, ok, res, reload, doneText) {
		if (el && typeof global.markSettingSaved === 'function') global.markSettingSaved(el, ok);
		$(msgSel).removeClass('alert-success alert-danger')
			.addClass(ok ? 'alert-success' : 'alert-danger')
			.text(ok ? (doneText || t('ui.js.saved')) : apiMessage(res, t('ui.js.saveFailed', 'Save failed'))).show();
		if ((!ok || doneText) && typeof reload === 'function') reload();
	};
})(window);
