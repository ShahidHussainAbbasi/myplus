/**
 * EX-2a — the Modules card (templates/fragments/module-switches.html), shared by every dashboard that has no
 * capability switches of its own. Lists the OPT-IN modules auth-service publishes; a change saves at once.
 *
 * Waits for the server (STANDARDS §0b is about money, but a module switch decides whether money screens exist):
 * the box is disabled while saving, reverts if the save is refused, and only then are the menus re-evaluated.
 */
(function (global) {
	'use strict';

	function tr(key, fallback) {
		return (typeof global.t === 'function' && typeof global.tHas === 'function' && global.tHas(key))
			? global.t(key) : fallback;
	}
	function esc(v) { return typeof global.escHtml === 'function' ? global.escHtml(v) : String(v == null ? '' : v); }
	function ctx() { return typeof global.serverContext === 'string' ? global.serverContext : '/'; }

	function msg(text, bad) {
		$('#moduleSwitchesMsg').text(text || '').css('color', bad ? '#b3261e' : '#2E7D32');
	}

	function render(rows) {
		var html = (rows || []).map(function (m) {
			var id = 'modsw_' + String(m.code).replace(/[^A-Za-z0-9]/g, '_');
			return '<div class="checkbox" style="margin:0 0 10px">'
				+ '<label for="' + esc(id) + '"><input type="checkbox" id="' + esc(id) + '" data-key="' + esc(m.key) + '"'
				+ (m.enabled ? ' checked' : '') + (m.locked ? ' disabled' : '') + '> '
				+ '<strong>' + esc(m.label) + '</strong></label>'
				+ (m.help ? '<div class="help-block" style="margin:2px 0 0 20px">' + esc(m.help) + '</div>' : '')
				+ '</div>';
		}).join('');
		$('#moduleSwitchesList').html(html || '<p class="text-muted">' + esc(tr('ui.js.modNone', 'No extra modules are available.')) + '</p>');
	}

	function loadModuleSwitches() {
		if (!$('#moduleSwitches').length) return;
		msg('');
		$.ajax({ url: ctx() + 'moduleSwitches', dataType: 'json' })
			.done(function (res) {
				if (!res || res.success !== true) { msg((res && res.message) || tr('ui.js.modLoadFailed', 'Could not load modules.'), true); return; }
				render(res.data);
			})
			.fail(function () { msg(tr('ui.js.modLoadFailed', 'Could not load modules.'), true); });
	}

	$(document).on('change', '#moduleSwitches input[type=checkbox][data-key]', function () {
		var $box = $(this), on = $box.is(':checked');
		$box.prop('disabled', true);
		msg(tr('ui.js.expSaving', 'Saving…'));
		$.ajax({ url: ctx() + 'saveModuleSwitch', type: 'POST', dataType: 'json',
			data: { key: $box.attr('data-key'), enabled: on ? 'true' : 'false' } })
			.done(function (res) {
				if (!res || res.success !== true) {
					$box.prop('checked', !on);
					msg((res && res.message) || tr('ui.js.saveFailed', 'Not saved'), true);
					return;
				}
				msg(tr('ui.js.saved', 'Saved'));
				if (typeof global.reloadCapabilities === 'function') global.reloadCapabilities();
			})
			.fail(function (xhr) {
				$box.prop('checked', !on);
				msg(typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, tr('ui.js.saveFailed', 'Not saved'))
					: tr('ui.js.saveFailed', 'Not saved'), true);
			})
			.always(function () { $box.prop('disabled', false); });
	});

	global.loadModuleSwitches = loadModuleSwitches;
})(window);
