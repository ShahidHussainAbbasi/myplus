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
 * Supported types: BOOL (checkbox), SELECT (dropdown), INT / TEXT (input).
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
		var common = ' id="' + esc(id) + '" data-key="' + esc(it.key) + '"'
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

		if (it.type === 'TEXT') {
			return '<input type="text" class="form-control" value="' + esc(it.value) + '"' + common + '/>';
		}

		// BOOL (default)
		return '<input type="checkbox"' + (String(it.value) === 'true' ? ' checked' : '') + common + '/>';
	}

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

			var html = '';
			Object.keys(groups).forEach(function (g) {
				html += '<h4 style="margin-top:18px">' + esc(g) + '</h4>';
				groups[g].forEach(function (it) {
					html += '<div class="form-group" style="margin-bottom:12px">'
						+ '<label class="control-label col-sm-5" for="' + esc(fieldId(opts.fieldPrefix, it.key)) + '">'
						+ esc(it.label) + '</label>'
						+ '<div class="col-sm-7">'
						+ controlFor(it, opts.fieldPrefix, opts.onChangeFn)
						+ '<div style="color:#7a889c;font-size:12px;margin-top:3px">' + esc(it.help || '') + '</div>'
						+ '</div></div>';
				});
			});

			$box.html('<div class="form-horizontal">' + html + '</div>');
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
