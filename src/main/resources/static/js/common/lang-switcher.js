/* ============================================================================
 * lang-switcher.js — opens/closes the language menu (templates/fragments/lang-switcher.html).
 *
 * One implementation for every page that includes the fragment. Kept deliberately tiny:
 * the actual language change is a plain link (?lang=xx) handled server-side by
 * LocaleChangeInterceptor, so no JavaScript is required to switch language — this only
 * provides the dropdown affordance. With JS off the menu still renders on focus/hover
 * paths and the links still work.
 * ========================================================================== */
(function (global) {
	'use strict';

	function closeAll(except) {
		var open = document.querySelectorAll('.lang-switch.is-open');
		for (var i = 0; i < open.length; i++) {
			if (open[i] !== except) {
				open[i].classList.remove('is-open');
				var btn = open[i].querySelector('.lang-switch__btn');
				if (btn) { btn.setAttribute('aria-expanded', 'false'); }
			}
		}
	}

	/* Called from the fragment's inline onclick, matching how the sidebar nav (snavToggle)
	 * is wired in this codebase. */
	global.langSwitchToggle = function (event) {
		if (event) { event.preventDefault(); event.stopPropagation(); }

		var box = event && event.currentTarget ? event.currentTarget.closest('.lang-switch') : null;
		if (!box) { return; }

		closeAll(box);
		var isOpen = box.classList.toggle('is-open');

		var btn = box.querySelector('.lang-switch__btn');
		if (btn) { btn.setAttribute('aria-expanded', isOpen ? 'true' : 'false'); }
	};

	document.addEventListener('click', function (e) {
		if (!e.target.closest || !e.target.closest('.lang-switch')) { closeAll(null); }
	});

	document.addEventListener('keydown', function (e) {
		if (e.key === 'Escape') { closeAll(null); }
	});
})(window);
