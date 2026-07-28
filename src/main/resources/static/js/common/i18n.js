/* ============================================================================
 * i18n.js — translated strings for the module scripts.
 *
 * The dictionary is rendered into the page by fragments/header.html from the SAME
 * message bundle Thymeleaf renders the markup with (see JsMessageSource), so a JS
 * alert and the label next to it can never drift apart or disagree about language.
 * Only the ui.js.* subset crosses to the browser.
 *
 * Usage:
 *     t('ui.js.enterQtyGreaterThanZero')
 *     t('ui.js.cannotReturnMoreThanSold', sold)      // {0} is replaced by sold
 *
 * Falls back to the key itself if a string is missing, which makes a gap obvious in
 * testing without throwing in front of a user mid-transaction.
 * ========================================================================== */
(function (global) {
	'use strict';

	var DICT = global.__MSG || {};

	/**
	 * Look up a key and substitute positional {0}, {1}, … placeholders.
	 * Placeholders are used rather than string concatenation because the argument's
	 * position differs between languages — in Urdu and Arabic the number often has to
	 * precede the noun that follows it in English.
	 */
	global.t = function (key) {
		var s = Object.prototype.hasOwnProperty.call(DICT, key) ? DICT[key] : key;
		if (arguments.length < 2) { return s; }

		var args = Array.prototype.slice.call(arguments, 1);
		return s.replace(/\{(\d+)\}/g, function (match, i) {
			var v = args[Number(i)];
			return (v === undefined || v === null) ? match : String(v);
		});
	};

	/** True when a key is actually present — for callers that want their own fallback. */
	global.tHas = function (key) {
		return Object.prototype.hasOwnProperty.call(DICT, key);
	};
})(window);
